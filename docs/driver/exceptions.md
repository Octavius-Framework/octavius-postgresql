# Error Handling and Exceptions

*A magistrate who rejected a petition did not simply wave the petitioner away. The refusal went into the record with its
grounds attached, the petitioner left knowing exactly which part of his claim had failed, and the court moved on to the
next case without adjourning. Octavius treats a rejected statement the same way: the exception carries what the server
actually said, down to the field it objected to, and a refusal does not close the session it was raised on.*

Octavius builds its error handling around a single base class — `OctaviusException` — that carries rich diagnostic context. Specific subclasses represent distinct categories of failure, so you're rarely left guessing what went wrong.

Three things are worth internalizing before the details:

* **The reason enum is a convenience layer, not a lossy one.** Most exceptions carry an enum identifying the exact reason for the failure, so you don't hand-parse `SQLSTATE` codes yourself. But nothing the server said is thrown away: `serverErrorMessage` still holds the complete parsed `ErrorResponse`.
* **A rejected statement does not desynchronize the connection.** When the *server* reports an error, the driver keeps reading the protocol stream until `ReadyForQuery`, *then* throws — so the session is still usable afterwards. The exception is I/O failure: a `NetworkException` means the socket itself is gone, and that connection is finished.
* **Which type you catch depends on the door you came in through.** The native session API throws `OctaviusException` subclasses, the raw JDBC surface throws `SQLExceptionWrapper`, Spring's `OctaviusTemplate` throws `OctaviusDataAccessException`. See [Crossing into JDBC and Spring](#crossing-into-jdbc-and-spring).

Contents:
* [The base class](#the-base-class)
* [From wire to exception](#from-wire-to-exception)
* [Message format and logging](#message-format-and-logging)
* [Query context](#query-context)
* [SQLSTATE routing](#sqlstate-routing)
* [Exception reference](#exception-reference)
* [Catching at the right altitude](#catching-at-the-right-altitude)
* [Crossing into JDBC and Spring](#crossing-into-jdbc-and-spring)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## The base class

Every failure the driver reports is an `OctaviusException`:

```kotlin
abstract class OctaviusException(
    message: String,
    val sqlState: String? = null,
    val serverErrorMessage: ServerErrorMessage? = null,
    cause: Throwable? = null,
    val path: MutableList<String> = mutableListOf()
) : RuntimeException(message, cause)
```

| Member                 | Type                  | What it holds                                                                                                                          |
|:-----------------------|:----------------------|:---------------------------------------------------------------------------------------------------------------------------------------|
| `message`              | `String`              | The machine-readable identifier, `EXCEPTION_NAME[:REASON_ENUM]` — never prose.                                                         |
| `sqlState`             | `String?`             | The five-character SQLSTATE. `null` for purely client-side failures that never reached the server.                                     |
| `serverErrorMessage`   | `ServerErrorMessage?` | The complete parsed `ErrorResponse` from PostgreSQL. `null` when the error originated in the driver.                                   |
| `queryContext`         | `QueryContext?`       | What *your application* executed — SQL, parameters, and their database-level forms. Attached on the way out.                           |
| `path`                 | `MutableList<String>` | Where the failure happened, innermost first — an attribute five levels down a composite, a step of a plan. Appended to on the way out. |
| `cause`                | `Throwable?`          | The underlying exception, where one exists (an `IOException` under a `NetworkException`, for example).                                 |
| `getDetailedMessage()` | `String?`             | The human-readable explanation, assembled per subclass. This is what the log block renders.                                            |

`path` and `queryContext` are the two things a frame can add to an exception **without replacing it**, which is why both live on the base class rather than on the subclass that happens to need them. Replacing an exception costs the type the caller catches on — a `ConstraintViolationException` restated as a mapping failure stops being the thing a retry loop matches — so a layer that knows *where* it was appends a segment and rethrows the exception it was given. Both are rendered into `toString()`: `path` reversed, outermost first, on a `PATH:` line.

`serverErrorMessage` is a plain data class mirroring the wire message field for field: `severity`, `code`, `message`, `detail`, `hint`, `position`, `internalPosition`, `internalQuery`, `where`, `schema`, `table`, `column`, `datatype`, `constraint`, `file`, `line`, `routine`. Most subclasses re-expose the fields that matter for their category as first-class properties (`constraint` on a constraint violation, `routine` on a permission denial), but the raw object is always there when you need something they don't surface.

The hierarchy is flat — fifteen concrete subclasses directly under `OctaviusException`, no intermediate layers to reason about.

## From wire to exception

### The server-side path

When PostgreSQL rejects something, it sends an `ErrorResponse` message. What happens next is deliberately unhurried:

1. **The executor records, it does not throw.** `QueryExecutor` parks the error in a local and keeps looping.
2. **The protocol is drained.** The loop continues until `ReadyForQuery` arrives, which also carries the current transaction status (`I` idle, `T` in transaction, `E` failed transaction) and updates the executor's `transactionStatus`. Skipping this would leave unread bytes in the socket and poison every later query on that connection.
3. **`ExceptionTranslator` classifies.** The SQLSTATE is matched against the [routing table](#sqlstate-routing), producing a concrete subclass plus its reason enum, with the `ServerErrorMessage` attached.
4. **The query layer attaches context.** `Query.withQueryContext` wraps every execution method; on the way out it catches the `OctaviusException` and fills in `queryContext` — *only if it is still null*, so the innermost frame that knows the real SQL wins and outer frames don't overwrite it.
5. **It surfaces.** Which type you actually catch depends on the API you entered through — see [Crossing into JDBC and Spring](#crossing-into-jdbc-and-spring).

This is what the server-error path buys you: a `ConstraintViolationException` leaves the connection as clean as a successful `INSERT` would. It says nothing about the I/O path — if the socket dies mid-drain, the loop never reaches step 2, `PgStream` latches `isBroken`, and a `NetworkException` propagates out of an unfinished exchange. That connection is done.

### Errors raised during row mapping

Decoding and mapping happen *while* the result stream is being consumed, so a mapping failure is handled the same careful way: the exception is parked in a local (`executionError`), the loop keeps draining until `ReadyForQuery`, and only then is it thrown. Two consequences worth knowing:

* If the server *also* reported an error, the server's error wins — `errorResponse` is checked before `executionError`.
* Exceptions that aren't `OctaviusException` get wrapped in a `MappingException(CONVERSION_ERROR)`. **Exceptions thrown by your own code** inside `forEachRow` / `forEachObject` / `forEachField` blocks are wrapped too, but under their own reason — `MappingException(BLOCK_FAILED)`, with details `"Exception in block: ..."` and the original as `cause`. If you need your own exception type to escape a streaming block, catch and re-throw it outside the block, or make it an `OctaviusException`.

### Errors that never reach the database

Plenty of failures are decided locally, before a single byte goes out. They have no `sqlState` (except where the driver picks a conventional one) and no `serverErrorMessage`:

| Situation                                                                                             | Exception                                            |
|:------------------------------------------------------------------------------------------------------|:-----------------------------------------------------|
| Unclosed quote / dollar-quote / comment found while parsing `@names`                                  | `StatementException(UNCLOSED_TOKEN)`                 |
| A named parameter present in the SQL has no value supplied                                            | `InvalidOperationException(MISSING_NAMED_PARAMETER)` |
| `fetchRowStrict` got 0 rows, `fetchRow` got 2+                                                        | `InvalidOperationException(INCORRECT_RESULT_SIZE)`   |
| Commit on an auto-commit session, closed statement, unwrap failure                                    | `InvalidOperationException`                          |
| Type missing from the registry, no codec for an OID                                                   | `TypeException`                                      |
| A codec's `toBinary` / `fromBinary` blew up                                                           | `CodecException`                                     |
| A column is missing, a non-nullable property got `null`                                               | `MappingException`                                   |
| Socket timeout, broken pipe, use of a closed connection                                               | `NetworkException`                                   |
| Handshake, authentication, SSL, version check — and a `DataSource` that would not hand a session over | `InitializationException`                            |

## Message format and logging

`message` always follows a predictable `EXCEPTION_NAME[:REASON_ENUM]` shape. It is an identifier, not a sentence — easy to spot at a glance and easy to filter on programmatically without string-matching gymnastics.

```text
CONSTRAINT_VIOLATION_EXCEPTION:UNIQUE_CONSTRAINT_VIOLATION
STATEMENT_EXCEPTION:SYNTAX_ERROR
CODEC_EXCEPTION:DECODING
PERMISSION_DENIED_EXCEPTION
```

The `:REASON` half is omitted for the three exceptions that have no reason enum — `PermissionDeniedException`, `DatabaseSystemException`, `UncategorizedDatabaseException`. `CodecException` puts its `CodecAction` (`ENCODING` / `DECODING`) in that slot instead.

The human-readable part lives in `toString()`, which renders a structured block: error type, SQL state, the subclass's detailed message, the query context, and — if there is one — the cause. Loggers call `toString()` when you pass the exception as a throwable, so **log the exception, not `e.message`**:

```kotlin
logger.error(e) { "Failed to enrol senator" }   // full block
logger.error { e.message }                      // just "CONSTRAINT_VIOLATION_EXCEPTION:..."
```

### Example: a unique violation

```kotlin
session.createNamedQuery("INSERT INTO senators (cognomen) VALUES (@cognomen)")
    .update("cognomen" to "Scipio")
```

```text
--------------------------------------------------------------------------------
MESSAGE: CONSTRAINT_VIOLATION_EXCEPTION:UNIQUE_CONSTRAINT_VIOLATION
SQLSTATE: 23505
EXCEPTION DETAILS:
Reason: A duplicate value was provided for a unique column or index (PostgreSQL 23505).
Database Message: duplicate key value violates unique constraint "senators_cognomen_key"
Details: Key (cognomen)=(Scipio) already exists.
Schema: roma
Table: senators
Constraint: senators_cognomen_key
================================================================================
DATABASE EXECUTION CONTEXT
================================================================================
HIGH-LEVEL SQL:
INSERT INTO senators (cognomen) VALUES (@cognomen)
--------------------------------------------------------------------------------
PARAMETERS:
cognomen - Scipio
--------------------------------------------------------------------------------
DATABASE-LEVEL SQL (SENT TO DB):
INSERT INTO senators (cognomen) VALUES ($1)
--------------------------------------------------------------------------------
DATABASE-LEVEL PARAMETERS:
1 - Scipio
================================================================================
--------------------------------------------------------------------------------
```

(The Senate, it turns out, already had a Scipio.)

### Example: a syntax error, with the caret

`StatementException` goes one step further. When the database (or the parameter parser) reports a `position`, it slices out the offending line and draws a caret under the exact character:

```text
--------------------------------------------------------------------------------
MESSAGE: STATEMENT_EXCEPTION:SYNTAX_ERROR
SQLSTATE: 42601
EXCEPTION DETAILS:
Reason: The SQL statement contains a syntax error.
Details: syntax error at or near "FRO"
Error at position 10:
SELECT * FRO senators
         ^
================================================================================
DATABASE EXECUTION CONTEXT
...
```

The caret is drawn against `queryContext.dbSql` when there is one, falling back to `sql`. For a named query that means the caret lands on the **transformed** statement (`SELECT $1 FRO ...`), because that is the string PostgreSQL counted positions in. Multi-line SQL is handled — only the line containing the error is printed, and the column is computed relative to that line.

## Query context

`QueryContext` is what makes a failure reproducible. It carries both altitudes of the same statement:

| Property       | Type                | Meaning                                                          |
|:---------------|:--------------------|:-----------------------------------------------------------------|
| `sql`          | `String`            | The high-level SQL your application wrote, `@names` and all.     |
| `parameters`   | `Map<String, Any?>` | The parameters as you supplied them.                             |
| `dbSql`        | `String?`           | The statement actually sent to the server, after transformation. |
| `dbParameters` | `List<Any?>?`       | The positional values actually bound.                            |

How it is populated depends on the query type and on *when* things broke:

| Query                                          | `sql`      | `parameters`                  | `dbSql`            | `dbParameters` |
|:-----------------------------------------------|:-----------|:------------------------------|:-------------------|:---------------|
| `createNativeQuery` (`$1` placeholders)        | as written | `{"1": …, "2": …}` positional | identical to `sql` | the values     |
| `createNamedQuery`, failing at execution       | as written | your named map                | the `$n` rewrite   | the values     |
| `createNamedQuery`, failing *before* rewriting | as written | your named map                | `null`             | `null`         |

That last row is the parser and missing-parameter case: an `UNCLOSED_TOKEN` or a `MISSING_NAMED_PARAMETER` is
detected before the SQL is transformed, so there is no database-level form to show.

Exceptions raised outside a query — connection setup, `session.commit()`, savepoint misuse — have
`queryContext == null`. Always treat it as nullable.

The properties hold your values as you passed them, unchanged. What is bounded is the **rendering** — the block
`toString()` prints, and the same one [the log uses](logging.md#parameter-values-are-a-property-not-a-level): a
`ByteArray` is named rather than dumped, a long string is cut, and a collection or array is walked only as far as the
budget allows, then counted (`[0, 1, 2, … +9990 more]`). That matters for a [bulk write](bulk-writes.md), where one
parameter legitimately holds ten thousand elements and printing it whole would build megabytes on the way out of a
failure. A class of your own still renders through its own `toString()`, which the driver cannot bound.

Bounded is not the same as redacted, and `queryContext` is a `var` for exactly that gap. `logParameterValues=false`
governs [the driver's own log lines](logging.md#parameter-values-are-a-property-not-a-level) and nothing else — an
exception is addressed to the caller, not to a file, so it carries the values whatever the property says. Where the
entry you write yourself must not hold them, replace the context before the logger renders it. `QueryContext` is a data
class, so that is one call:

```kotlin
catch(e: OctaviusException) {
    e.queryContext = e.queryContext?.copy(parameters = emptyMap(), dbParameters = null)
    logger.error(e) { "Census update failed" }
}
```

The statement survives, which is usually what makes the entry worth keeping; only the values go.

## SQLSTATE routing

`ExceptionTranslator` is a single ordered `when` over the SQLSTATE string. Order matters: the first matching branch wins, which is how the specific codes (`40002`, `42501`, `55P03`, `57014`) escape their class-wide defaults.

| #  | SQLSTATE                   | Becomes                                                   |
|:---|:---------------------------|:----------------------------------------------------------|
| 1  | `08*`                      | `NetworkException(CONNECTION_ERROR)`                      |
| 2  | `22*`                      | `DataException` — reason per code, see below              |
| 3  | `28*`                      | `InitializationException(SERVER_REJECTED_CREDENTIALS)`    |
| 4  | `21*`, `0A*`, `3D*`, `3F*` | `StatementException(INVALID_DEFINITION)`                  |
| 5  | `23*`                      | `ConstraintViolationException` — reason per code          |
| 6  | `25P03`, `25P04`           | `ExecutionAbortedException(TRANSACTION_TIMEOUT)`          |
| 7  | `25*` (everything else)    | `TransactionStateException` — reason per code             |
| 8  | `40002`                    | `ConstraintViolationException(UNKNOWN)`                   |
| 9  | `40*` (everything else)    | `ConcurrencyException` — `40001`, `40P01`, else `UNKNOWN` |
| 10 | `42501`                    | `PermissionDeniedException`                               |
| 11 | `42*` (everything else)    | `StatementException` — reason per code, see below         |
| 12 | `54*`                      | `StatementException(SYNTAX_ERROR)`                        |
| 13 | `55P03`                    | `ConcurrencyException(LOCK_NOT_AVAILABLE)`                |
| 14 | `55*` (everything else)    | `DatabaseSystemException`                                 |
| 15 | `57014`                    | `ExecutionAbortedException(QUERY_CANCELED)`               |
| 16 | `57*`, `53*`, `58*`, `XX*` | `DatabaseSystemException`                                 |
| 17 | `P0002`, `P0003`, `P0004`  | `RoutineAssertionException` — reason per code             |
| 18 | `P0*` (everything else)    | `RoutineRaiseException`                                   |
| 19 | anything else              | `UncategorizedDatabaseException`                          |

**Class 22 (data) → `DataExceptionReason`**

| Codes                                                | Reason                   |
|:-----------------------------------------------------|:-------------------------|
| `22001`, `22008`, `22015`                            | `DATA_TRUNCATION`        |
| `22003`, `22022`                                     | `NUMERIC_OUT_OF_RANGE`   |
| `22012`                                              | `DIVISION_BY_ZERO`       |
| `22007`, `22P02`, `22P03`, `22018`                   | `INVALID_FORMAT`         |
| `2202E`                                              | `ARRAY_SUBSCRIPT_ERROR`  |
| `22004`, `22002`                                     | `NULL_VALUE_NOT_ALLOWED` |
| `2201B`                                              | `REGEX_ERROR`            |
| `22019`, `2200D`, `22025`, `22P06`, `2200C`, `2200B` | `ESCAPE_CHARACTER_ERROR` |
| `2200L`, `2200M`, `2200N`, `2200S`, `2200T`          | `XML_ERROR`              |
| `2203*`                                              | `JSON_ERROR`             |
| anything else in class 22                            | `UNKNOWN`                |

**Class 42 (syntax / access) → `StatementExceptionReason`**

| Codes                                                                           | Reason               |
|:--------------------------------------------------------------------------------|:---------------------|
| `42601`, `42602`, `42622`, `42939`, `42000`                                     | `SYNTAX_ERROR`       |
| `42703`, `42883`, `42P01`, `42P02`, `42704`                                     | `UNDEFINED_OBJECT`   |
| `42701`, `42723`, `42P03`, `42P04`, `42P05`, `42P06`, `42P07`, `42712`, `42710` | `DUPLICATE_OBJECT`   |
| `42702`, `42725`, `42P08`, `42P09`                                              | `AMBIGUOUS_OBJECT`   |
| `42804`, `42P18`, `42846`, `42P21`, `42P22`                                     | `DATA_TYPE_ERROR`    |
| anything else in class 42                                                       | `INVALID_DEFINITION` |

## Exception reference

### 1. `ConstraintViolationException`

**Thrown when:** a database constraint is violated during execution (e.g. inserting a duplicate primary key).
**Raised by:** the server, SQLSTATE class `23` (plus `40002`).
**Properties:** `reason`, `dbMessage`, `details`, `where`, `schema`, `table`, `column`, `constraint`.

Which of those the database actually fills in varies by violation: a unique violation names the `constraint`, `table` and `schema` and puts the offending key in `details`; a not-null violation names the `column`. Read them as nullable and prefer `constraint` over parsing `dbMessage`.

| Reason (`ConstraintViolationExceptionReason`) | SQLSTATE         | Description                                                                                                                            |
|:----------------------------------------------|:-----------------|:---------------------------------------------------------------------------------------------------------------------------------------|
| `UNIQUE_CONSTRAINT_VIOLATION`                 | `23505`          | Duplicate value provided for a unique column or index.                                                                                 |
| `FOREIGN_KEY_VIOLATION`                       | `23503`          | Value does not exist in the referenced table, or a row still referenced was deleted or had its key changed.                            |
| `RESTRICT_VIOLATION`                          | `23001`          | Row referenced through an `ON DELETE RESTRICT` or `ON UPDATE RESTRICT` foreign key was deleted or had its key changed.                 |
| `NOT_NULL_VIOLATION`                          | `23502`          | Null value provided for a non-nullable column.                                                                                         |
| `CHECK_CONSTRAINT_VIOLATION`                  | `23514`          | Value fails a CHECK constraint.                                                                                                        |
| `EXCLUSION_CONSTRAINT_VIOLATION`              | `23P01`          | Exclusion constraint violation (e.g. overlapping ranges).                                                                              |
| `UNKNOWN`                                     | `23000`, `40002` | Unmapped or generic constraint violation. In practice these appear only when raised inside a trigger or procedure, or by an extension. |

### 2. `DataException`

**Thrown when:** the query itself is well-formed, but the runtime *values* trigger a database error — numeric overflow, malformed JSON, a text literal that won't parse as the target type. Typically a parameter problem rather than a SQL problem.
**Raised by:** the server, SQLSTATE class `22`.
**Properties:** `reason`, `dbMessage`, `details`, `where`.

| Reason (`DataExceptionReason`) | Description                                                |
|:-------------------------------|:-----------------------------------------------------------|
| `DATA_TRUNCATION`              | String, interval, or datetime value truncated/overflowed.  |
| `NUMERIC_OUT_OF_RANGE`         | Numeric value is out of bounds for the target data type.   |
| `DIVISION_BY_ZERO`             | Attempted to divide by zero.                               |
| `INVALID_FORMAT`               | Invalid text or binary representation for the type.        |
| `ARRAY_SUBSCRIPT_ERROR`        | Array subscript out of bounds or bad dimensions.           |
| `NULL_VALUE_NOT_ALLOWED`       | Null value provided where prohibited by a data constraint. |
| `JSON_ERROR`                   | Error while parsing or operating on JSON/JSONB data.       |
| `XML_ERROR`                    | Error in XML operations.                                   |
| `ESCAPE_CHARACTER_ERROR`       | Invalid escape character or sequence.                      |
| `REGEX_ERROR`                  | Invalid regular expression.                                |
| `UNKNOWN`                      | Generic class-22 error with no specific mapping.           |

### 3. `StatementException`

**Thrown when:** SQL parsing, planning, or execution fails — and also for a handful of client-side statement problems.
**Raised by:** the server (classes `42`, `54`, `21`, `0A`, `3D`, `3F`) **and** the driver's own SQL parser (`SqlParameterParser`).
**Properties:** `reason`, `details`, `position` — the 1-based character position of the error, used to render the caret.

| Reason (`StatementExceptionReason`) | Origin | Description                                                                                           |
|:------------------------------------|:-------|:------------------------------------------------------------------------------------------------------|
| `SYNTAX_ERROR`                      | server | SQL syntax error (also class `54`, program limits exceeded).                                          |
| `UNCLOSED_TOKEN`                    | driver | A quote, dollar-quoted body or comment left open while scanning for `@params`. `details` names which. |
| `UNDEFINED_OBJECT`                  | server | Referenced function, column, or table does not exist.                                                 |
| `DUPLICATE_OBJECT`                  | server | Object already exists (DDL statements).                                                               |
| `AMBIGUOUS_OBJECT`                  | server | Ambiguous reference (e.g. an unqualified column across JOINs).                                        |
| `DATA_TYPE_ERROR`                   | server | Type mismatch at the query level.                                                                     |
| `INVALID_DEFINITION`                | server | Invalid definition or object state; also the class-42 catch-all.                                      |

Every reason here is about the statement itself, and `position` is the evidence of it: server-reported or parser-reported, there is somewhere in the SQL to point at. A call that asked for something the statement cannot give — a parameter left out of the map, a row count the chosen terminal forbids — is `InvalidOperationException` instead.

### 4. `InitializationException`

**Thrown when:** no session could be obtained at all — the driver failed to establish a connection or authenticate, or a `DataSource` would not hand one over.
**Raised by:** `OctaviusConnectionFactory`, `Authenticator`, `SslNegotiator`, `PgStream` — by the translator for any SQLSTATE class `28`, and by `getOctaviusSession()` for anything a `DataSource` refuses.
**Properties:** `reason`, `details`, `cause`.

| Reason (`InitializationExceptionReason`) | Description                                                                                                                                        |
|:-----------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------|
| `SERVER_REJECTED_CREDENTIALS`            | Invalid username or password.                                                                                                                      |
| `UNSUPPORTED_MECHANISM`                  | No mechanism the driver implements, or channel binding was unavailable.                                                                            |
| `UNSUPPORTED_PASSWORD_ENCRYPTION`        | Server requested cleartext or MD5 rather than SCRAM-SHA-256.                                                                                       |
| `PROTOCOL_VIOLATION`                     | Unexpected message received during the authentication exchange.                                                                                    |
| `MISSING_PROTOCOL_PARAMETER`             | A required field was missing from the server's authentication challenge.                                                                           |
| `SSL_ERROR`                              | TLS negotiation failed, or the server does not support it.                                                                                         |
| `UNSUPPORTED_SERVER_VERSION`             | PostgreSQL older than 18 — Octavius speaks Wire Protocol v3.2 exclusively.                                                                         |
| `CONNECTION_ERROR`                       | General connection failure before authentication could begin — and the catch-all for a `DataSource` that could not open one.                       |
| `CONNECTION_UNAVAILABLE`                 | The data source had none to give rather than failing to open one — a pool that ran out of time waiting for a free one. Nothing reached the server. |

> [!NOTE]
> The authorization statements a live session runs do not produce this — a permission refused mid-session is
> `PermissionDeniedException`. This one is about not having a working session in the first place.

> [!NOTE]
> **A pool timeout is `CONNECTION_UNAVAILABLE`, not `CONNECTION_ERROR`.** From the call site the two look
> alike — no session either way — but they are not the same failure. One is the database or the network
> refusing; the other never reached either, and is the application asking for more connections at once than it
> has. The distinction is read from JDBC's own `SQLTransientConnectionException` rather than guessed at from
> the message, so any data source that reports a transient failure lands on it, not only HikariCP.
>
> `details` and the `cause` carry the data source's own account, which for a pool includes its census:
>
> ```text
> Reason: No connection was available. The data source had none to give rather than failing to open one.
> Details: Could not obtain a session from com.zaxxer.hikari.HikariDataSource: probe - Connection is not
> available, request timed out after 1001ms (total=1, active=1, idle=0, waiting=0)
> ```
>
> `total`, `active` and `waiting` are what say whether the pool is simply too small for what the application
> is doing. [Nesting is one way to make it too
> small](../client/transactions-failures.md#propagation).

### 5. `NetworkException`

**Thrown when:** a physical network error disrupts communication, or you touch a connection that is already gone.
**Raised by:** `PgStream` (socket layer), `OctaviusConnection.checkClosed()`, and the translator for SQLSTATE class `08`.
**Properties:** `reason`, `details`, `cause` (usually the original `IOException` / `SocketTimeoutException` / `EOFException`).

| Reason (`NetworkExceptionReason`) | Typical SQLSTATE | Description                                                |
|:----------------------------------|:-----------------|:-----------------------------------------------------------|
| `CONNECTION_ERROR`                | `08006`          | General network error, or the underlying stream is broken. |
| `CONNECTION_TIMEOUT`              | `08006`          | Read or connect timed out.                                 |
| `CONNECTION_CLOSED_BY_PEER`       | `08006`          | Server closed the connection abruptly (`EOF`).             |
| `CONNECTION_CLOSED`               | `08003`          | Operation attempted on an already-closed connection.       |
| `CONNECTION_ABORTED`              | `08000`          | Connection explicitly aborted by the client.               |

Once the socket breaks, `PgStream.isBroken` latches. The next `checkClosed()` flips the connection to closed and throws, which is exactly the signal HikariCP needs to evict it from the pool instead of handing it to the next caller.

### 6. `ConcurrencyException`

**Thrown when:** a transaction fails because of what other transactions were doing.
**Raised by:** the server, SQLSTATE class `40` (except `40002`) and `55P03`.
**Properties:** `reason`.

| Reason (`ConcurrencyExceptionReason`) | SQLSTATE    | Description                                                    |
|:--------------------------------------|:------------|:---------------------------------------------------------------|
| `LOCK_NOT_AVAILABLE`                  | `55P03`     | Required lock could not be obtained (`NOWAIT`).                |
| `DEADLOCK_DETECTED`                   | `40P01`     | Transaction deadlock detected and broken by the server.        |
| `SERIALIZATION_FAILURE`               | `40001`     | Serialization conflict under `REPEATABLE READ`/`SERIALIZABLE`. |
| `UNKNOWN`                             | other `40*` | Unmapped rollback error.                                       |

This is the one category that is routinely **retryable** — see the retry loop in [Catching at the right altitude](#catching-at-the-right-altitude).

### 7. `ExecutionAbortedException`

**Thrown when:** execution is stopped by the server rather than failing on its own merits.
**Raised by:** the server, `25P03` / `25P04` / `57014`.
**Properties:** `reason`.

| Reason (`ExecutionAbortedExceptionReason`) | SQLSTATE         | Description                                                           |
|:-------------------------------------------|:-----------------|:----------------------------------------------------------------------|
| `TRANSACTION_TIMEOUT`                      | `25P03`, `25P04` | `idle_in_transaction_session_timeout` or `transaction_timeout` fired. |
| `QUERY_CANCELED`                           | `57014`          | Cancelled via `statement_timeout` or `session.cancelQuery()`.         |

### 8. `TransactionStateException`

**Thrown when:** the statement is fine and the transaction it arrived in is not — one an earlier error already doomed, one declared `READ ONLY`, one open where the command forbids it, or none open where the command needs one.
**Raised by:** the server, SQLSTATE class `25` apart from `25P03` / `25P04`.
**Properties:** `reason`, `dbMessage`, `hint`.

There is no `position` here, and that is the whole reason this is not a `StatementException`: the server rejects these before the statement is considered on its own merits, so it sends nothing to point at. Nothing about the SQL is wrong.

| Reason (`TransactionStateExceptionReason`) | SQLSTATE    | Description                                                                                     |
|:-------------------------------------------|:------------|:------------------------------------------------------------------------------------------------|
| `IN_FAILED_TRANSACTION`                    | `25P02`     | An earlier error doomed the transaction; everything is refused until it is rolled back.         |
| `READ_ONLY_TRANSACTION`                    | `25006`     | A write inside a transaction declared `READ ONLY`.                                              |
| `NO_ACTIVE_TRANSACTION`                    | `25P01`     | The statement requires an open transaction and there is none.                                   |
| `ACTIVE_TRANSACTION`                       | `25001`     | The statement refuses to run inside a transaction block, and one is open.                       |
| `UNKNOWN`                                  | other `25*` | Another class-25 state, including the two-phase-commit codes Octavius does not otherwise touch. |

`IN_FAILED_TRANSACTION` is a consequence rather than a cause. After any error inside an explicit transaction PostgreSQL discards the work and refuses every further command until a `ROLLBACK` arrives, so this exception only says the session never left that state — **the failure worth reading is the earlier one**. See [One failed statement poisons the whole transaction](#practical-rules-and-gotchas).

The two class-25 timeouts are elsewhere: a transaction the server ended on a timer is `ExecutionAbortedException(TRANSACTION_TIMEOUT)`, alongside the statement timeout it belongs with.

### 9. `RoutineRaiseException`

**Thrown when:** a PL/pgSQL routine raises an error of its own — `RAISE EXCEPTION`, which is usually your own business rules expressed where the data is.
**Raised by:** the server, SQLSTATE `P0001`, `P0000`, and anything else in class `P0` that is not an assertion failure.
**Properties:** `dbMessage`, `dbDetail`, `hint`, `where`. No reason enum — its `message` is simply `ROUTINE_RAISE_EXCEPTION`.

`where` is the PL/pgSQL call stack (`PL/pgSQL function elect_consul(text) line 12 at RAISE`), which is what makes this exception genuinely debuggable. `dbMessage` is the text you passed to `RAISE`, `dbDetail` and `hint` the `DETAIL` and `HINT` clauses.

There is no reason enum because there is nothing to tell apart. A plain `RAISE EXCEPTION` reports `P0001`; `P0000` is reachable only by asking for it — `RAISE ... USING ERRCODE = 'plpgsql_error'` — so it is another deliberate raise rather than an unclassified failure. Which one it was is `sqlState`.

> [!TIP]
> `RAISE EXCEPTION` accepts `USING ERRCODE = '...'`. Raise a domain-specific SQLSTATE and it routes through the normal
> table — `ERRCODE = '23505'` will reach your application as a `ConstraintViolationException`, not as this.

### 10. `RoutineAssertionException`

**Thrown when:** an assertion inside a PL/pgSQL routine turns out to be false — an `INTO STRICT` that matched no row or several, or an `ASSERT` that did not hold.
**Raised by:** the server, SQLSTATE `P0002`, `P0003`, `P0004`.
**Properties:** `reason`, `dbMessage`, `dbDetail`, `hint`, `where`.

| Reason (`RoutineAssertionExceptionReason`) | SQLSTATE | Description                                      |
|:-------------------------------------------|:---------|:-------------------------------------------------|
| `NO_DATA_FOUND`                            | `P0002`  | `SELECT INTO STRICT` returned no rows.           |
| `TOO_MANY_ROWS`                            | `P0003`  | `SELECT INTO STRICT` returned more than one row. |
| `ASSERT_FAILURE`                           | `P0004`  | An `ASSERT` failed during execution.             |

Separate from `RoutineRaiseException` because it says something different. A `RAISE` is the routine deciding; these are the routine having claimed something the data then falsified, which makes them defects in the database code — the same reading that has a `fetch*Strict` finding no row raise `InvalidOperationException(INCORRECT_RESULT_SIZE)` rather than return `null`. This is that failure one level down, asserted in PL/pgSQL rather than in Kotlin.

See [Functions and Procedures](functions-procedures.md) for calling conventions.

### 11. `PermissionDeniedException`

**Thrown when:** the database user lacks the privileges for an action or object.
**Raised by:** the server, SQLSTATE `42501`.
**Properties:** `dbMessage`, `schema`, `table`, `column`, `datatype`, `routine`. No reason enum — its `message` is simply `PERMISSION_DENIED_EXCEPTION`.

The object-identifying fields come straight from the server's error message, so they pinpoint what was refused rather than making you parse `permission denied for table ...`.

### 12. `InvalidOperationException`

**Thrown when:** the driver is asked to do something not allowed in its current state. Purely client-side — no `sqlState`, no `serverErrorMessage`.
**Raised by:** `OctaviusConnection`, `OctaviusStatement`, `OctaviusSavepoint`, `OctaviusDataSource`, `LargeObject`, `CopyManager`, `QueryExecutor`, `NamedParameterQuery`, `NativeQuery`.
**Properties:** `reason`, `details`. Raised inside a query it still carries the `queryContext`, the way every driver exception does.

| Reason (`InvalidOperationExceptionReason`) | Description                                                                                                                                                                                                                                                                                |
|:-------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AUTO_COMMIT_VIOLATION`                    | `commit()`, `rollback()` or a savepoint attempted while auto-commit is enabled.                                                                                                                                                                                                            |
| `COMMIT_OF_FAILED_TRANSACTION`             | `commit()`, or switching auto-commit back on, in a transaction an earlier error aborted. The server would carry the `COMMIT` out as a `ROLLBACK` and report nothing, so nothing is sent; the transaction stays `FAILED` until it is rolled back.                                           |
| `INVALID_SAVEPOINT`                        | Savepoint is unknown, already released, or belongs to another connection.                                                                                                                                                                                                                  |
| `RESOURCE_CLOSED`                          | The statement, Large Object or `COPY` handle is already closed. `details` names it; handles are single-use, so the answer is a new one.                                                                                                                                                    |
| `INVALID_ARGUMENT`                         | An argument is not acceptable — a negative timeout, a null SQL string, an unsupported isolation level, a non-positive COPY `bufferSize`, a `PgTyped` wrapping another, a `PgRecord` read out of a result and handed back as a parameter. `details` names the rejected value.               |
| `MISSING_NAMED_PARAMETER`                  | A `@name` in the SQL had no value in the supplied map. `details` names the parameter.                                                                                                                                                                                                      |
| `UNWRAP_ERROR`                             | JDBC `unwrap()` to an interface this object does not implement.                                                                                                                                                                                                                            |
| `FEATURE_NOT_SUPPORTED`                    | A legacy JDBC feature Octavius deliberately does not implement.                                                                                                                                                                                                                            |
| `UNEXPECTED_RESULT`                        | `execute()`/`update()` received result rows — use a `fetch*` method for DQL, or `execute(ignoreRows = true)` to drop them. Also raised when a `COPY` did not start, or when a `DataRow` arrives before its `RowDescription`.                                                               |
| `INCORRECT_RESULT_SIZE`                    | `fetch*Strict` found 0 rows, or a single-row fetch found 2+ — which is why single-row fetches request `maxRows = 2`. The statement ran; the terminal chosen for it is what does not fit.                                                                                                   |
| `CONNECTION_BUSY`                          | The connection is already carrying an exchange and can carry only one — a `COPY` still open, or a statement still being read, usually a query issued from a `forEach` block or a converter. `details` says which, and how to let it finish. See [COPY](copy.md) and [Queries](queries.md). |

### 13. `TypeException`

**Thrown when:** type resolution fails in the registry.
**Raised by:** `TypeDictionary`, `ContainerFactory`, `ParameterSerializer`.
**Properties:** `reason`, `oid`, `typeName`, `details`.

| Reason (`TypeExceptionReason`)   | Description                                                                                                                    |
|:---------------------------------|:-------------------------------------------------------------------------------------------------------------------------------|
| `TYPE_NOT_FOUND`                 | Type is missing from the registry — often a `CREATE TYPE` executed after the catalog was loaded; call `session.reloadTypes()`. |
| `NOT_A_CONTAINER`                | The OID is not a composite/array/enum/range container.                                                                         |
| `MISSING_CODEC`                  | The driver has no codec for that OID (see [Type System](type-system.md#base-types-the-driver-does-not-implement)).             |

### 14. `CodecException`

**Thrown when:** encoding or decoding a value against PostgreSQL's binary format fails. Every codec call is routed through `encodeSafely` / `decodeSafely`, which catch *anything* the codec throws and re-wrap it here with the original as `cause`.
**Properties:** `action`, `value`, `name`, `schema`, `oid`, `kotlinClass`.

| Action (`CodecAction`) | Description                                                          |
|:-----------------------|:---------------------------------------------------------------------|
| `ENCODING`             | Failed to encode the Kotlin object into PostgreSQL's representation. |
| `DECODING`             | Failed to decode PostgreSQL's bytes into a Kotlin object.            |

`value` is truncated before it is stored — a `ByteArray` is reported as `ByteArray(n bytes)` and decoding keeps at most the first 100 bytes; a `toString()` longer than 100 characters is cut with an ellipsis. Diagnostics stay readable and a 40 MB `bytea` never ends up in your log file.

### 15. `MappingException`

**Thrown when:** a conversion or object-mapping step fails, on either side of the wire.
**Raised by:** `ResultMapper`, `ReflectionMappingUtils`, and the `PgComposite` / `PgRecord` / `PgArray` / `PgRange` accessors.
**Properties:** `reason`, `details`, and `path` from the base class.

| Reason (`MappingExceptionReason`) | Description                                                                                                                                                       |
|:----------------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `COLUMN_NOT_FOUND`                | The requested column, composite attribute, record field or array element does not exist — by name, or at that position.                                           |
| `REQUIRED_ATTRIBUTE_MISSING`      | The database returned `NULL` (or nothing) for a non-nullable Kotlin property.                                                                                     |
| `NO_CONVERTER_FOUND`              | No converter registered for the source/target type pair.                                                                                                          |
| `CONVERSION_ERROR`                | Cast or conversion failed, including a converter returning a type other than the one requested.                                                                  |
| `BLOCK_FAILED`                    | The block handed to `forEachRow` / `forEachObject` / `forEachField` threw something of its own; it is the `cause`.                                        |

`path` accumulates as the exception unwinds through nested structures — each frame appends its own key name — and is printed reversed, outermost first: `PATH: consul -> province -> founded`. That tells you *which* field five levels down in a nested composite was the problem. It is [the base class's](#the-base-class), so a mapping failure inside a [transaction plan](../client/plans.md) reads `PATH: step 3 -> parameter 'amount' -> map #1 -> founded` — the mapper's own segments, under the ones the plan added.

It runs the whole way down and the whole way up. The column is the outermost segment whichever route reached it — `fetchObjects` mapping the row, `fetchField`, or a `Row.get` of your own — and a container's own accessor is the innermost, so `composite.get<Int>("city")` inside a hand-written converter reads `PATH: residence -> city` rather than stopping at `residence`. A record's fields and an array's elements have only a position to give, so they give that: `[0]`, `[1]`. A range's bounds are `lower` and `upper`.

What gets no segment is a lookup that found nothing. `COLUMN_NOT_FOUND` — an index out of bounds, a name the composite does not carry — is not a location, and a path saying otherwise would read as though the driver had found something there and failed on it. The name you asked for is in the message instead.

### 16. `DatabaseSystemException`

**Thrown when:** the database engine itself is in trouble rather than your query being wrong — out of memory, disk full, configuration limits, internal errors.
**Raised by:** the server, SQLSTATE classes `53`, `55` (except `55P03`), `57` (except `57014`), `58`, `XX`.
**Properties:** `details` — a pre-formatted string that already includes the SQLSTATE and the server's message. No reason enum.

### 17. `UncategorizedDatabaseException`

**Thrown when:** a database error arrives with a SQLSTATE no branch of the routing table claims.
**Properties:** `details`. No reason enum.

Reaching for `serverErrorMessage` is the right move here — it holds everything the server said, and `sqlState` tells you which code slipped through.

## Catching at the right altitude

Three levels, pick per site.

**Everything.** For logging, a global handler, or a transaction wrapper:

```kotlin
try {
    session.transaction.required { /* ... */ }
} catch (e: OctaviusException) {
    logger.error(e) { "Database work failed" }
    throw e
}
```

**A category.** When the class alone tells you what to do:

```kotlin
try {
    session.createNamedQuery("INSERT INTO senators (cognomen) VALUES (@cognomen)")
        .update("cognomen" to cognomen)
} catch (e: ConstraintViolationException) {
    throw SenatorAlreadyEnrolled(e.constraint, e)
}
```

**A specific reason.** When the distinction inside a category is what you're branching on — no SQLSTATE string comparisons required:

```kotlin
catch (e: ConstraintViolationException) {
    when (e.reason) {
        ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION -> conflict(e.constraint)
        ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION       -> badReference(e.table)
        else                                                           -> throw e
    }
}
```

### Retrying concurrency failures

`SERIALIZATION_FAILURE` and `DEADLOCK_DETECTED` mean "nothing was wrong with your statement, the timing was unlucky" — the same work may well succeed on a second attempt. Getting the retry right takes a little care about what state the transaction is actually in.

On such an error PostgreSQL **dooms** the transaction: everything it did is discarded, and the session moves to
failed-transaction state (`ReadyForQuery` reports `E`), rejecting every further command with `25P02` →
`TransactionStateException(IN_FAILED_TRANSACTION)`. What it does *not* do is end the transaction block — someone still
has to send `ROLLBACK`. That someone is whoever owns the boundary: `transaction.required { }` if it opened the
transaction, `session.rollback()` if you are driving `autoCommit = false` yourself.

So a retry has to restart the *whole* transaction — only a new transaction gets a new snapshot, which is the entire
point under `REPEATABLE READ` / `SERIALIZABLE` — which puts the retry wrapper around the frame that owns the boundary:

```kotlin
fun <T> withRetry(attempts: Int = 3, block: () -> T): T {
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: ConcurrencyException) {
            if (e.reason == ConcurrencyExceptionReason.UNKNOWN) throw e
            Thread.sleep(50L shl it) // back off: 50ms, 100ms, ...
        }
    }
    return block()
}

val consul = withRetry {
    session.transaction.required {
        createNamedQuery("UPDATE aerarium SET balance = balance - @amount WHERE province_id = @id")
            .update("amount" to 100, "id" to 1)
        createNamedQuery("SELECT name FROM consuls WHERE id = @id").fetchFieldStrict<String>("id" to 1)
    }
}
```

Wrapping a `required { }` that merely *joined* an outer transaction retries nothing useful — the boundary is somewhere further out, so each attempt replays into the same doomed transaction. Put the retry where the transaction actually begins.

## Crossing into JDBC and Spring

The exception you catch depends on which door you came in through.

| Entry point                                                                         | You catch                                               |
|:------------------------------------------------------------------------------------|:--------------------------------------------------------|
| `OctaviusSession` — `createNativeQuery`, `createNamedQuery`, `commit`, `rollback` … | `OctaviusException` subclasses, unchanged               |
| A raw `java.sql.Connection` (`dataSource.connection`)                               | `SQLExceptionWrapper` (a `java.sql.SQLException`)       |
| Spring's `OctaviusTemplate`                                                         | `OctaviusDataAccessException` (a `DataAccessException`) |
| `HikariDataSource(config)` — building the pool itself                               | `HikariPool.PoolInitializationException`, Hikari's own  |

### `SQLExceptionWrapper`

The JDBC surface must throw `SQLException` — connection pools depend on it to inspect SQLSTATE, evict dead connections, and decide what is recoverable. So `OctaviusConnection` wraps:

```kotlin
class SQLExceptionWrapper(val wrappedException: OctaviusException)
    : SQLException(wrappedException.message, wrappedException.sqlState)
```

The wrapper carries the message and SQLSTATE, but **not** the cause chain — `wrapper.cause` is `null`. Reach for
`wrapper.wrappedException` to get back the typed exception with its context and stack trace intact:

```kotlin
try {
    dataSource.connection.use { /* raw JDBC */ }
} catch (e: SQLExceptionWrapper) {
    val octavius = e.wrappedException
    logger.error(octavius) { "..." }
}
```

Going the other way, `OctaviusSessionImpl` unwraps automatically: session-level operations that delegate to the JDBC
connection (`autoCommit`, `commit()`, `rollback()`, `transactionIsolationLevel`, `networkTimeout`, …) catch
`SQLExceptionWrapper` and re-throw the original. **Session API users never see the wrapper.**

### Spring

`OctaviusExceptionTranslator` plugs into Spring's `SQLExceptionTranslator` contract. It searches the incoming
`SQLException` and its entire cause chain for anything Octavius-shaped — a `SQLExceptionWrapper`, or a bare
`OctaviusException` such as the `InitializationException` a pool reports when it could not open a connection at all —
and produces an `OctaviusDataAccessException` carrying the original. Searching the whole chain is what keeps a driver
failure recognizable after HikariCP has wrapped it in an exception of its own. Anything it doesn't recognize falls
through to Spring's own `SQLStateSQLExceptionTranslator`, so you never lose the standard hierarchy.

**Spring users never see the wrapper either**: whatever the layers on the way, `octaviusException` is the driver's
exception itself.

`OctaviusDataAccessException` keeps the original available as `octaviusException`:

```kotlin
@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(OctaviusDataAccessException::class)
    fun handle(ex: OctaviusDataAccessException): ResponseEntity<Map<String, String>> {
        val root = ex.octaviusException

        if (root is ConstraintViolationException &&
            root.reason == ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "Already enrolled.", "constraint" to (root.constraint ?: "")))
        }

        if (root is TransactionStateException &&
            root.reason == TransactionStateExceptionReason.READ_ONLY_TRANSACTION) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Cannot write in a read-only transaction."))
        }

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to "Unexpected database error."))
    }
}
```

See [Spring Integration](spring-integration.md) for the surrounding configuration.

### Digging it out yourself

The translator above only runs where Spring is holding the exception. The one place nothing does it for you is *
*building the pool** — `HikariDataSource(config)` opens a connection to check the configuration works, and a driver
failure there comes back as Hikari's `PoolInitializationException`, thrown from a Hikari constructor you called
directly. That is correct layering rather than a gap: you called their API, you get their exception, and no amount of
driver code changes that.

What the driver does provide is the same tool its own translator uses. `findOctaviusCause()` is an extension on
`Throwable`, not on `SQLException`, so it works on anything:

```kotlin
import io.github.octaviusframework.driver.exception.findOctaviusCause

val pool = try {
    HikariDataSource(config)
} catch (e: HikariPool.PoolInitializationException) {
    // Hikari records the driver's exception as the cause, so the real reason is one call away:
    // bad credentials, a server below the version floor, TLS the server would not agree to.
    throw e.findOctaviusCause() ?: e
}
```

It walks the receiver first and then the cause chain, returning the driver's own exception — unwrapping a
`SQLExceptionWrapper` where it finds one — or `null` when the failure did not start in the driver. The walk is
depth-bounded, so a self-referential chain ends rather than spinning.

Outside pool construction you rarely need it: [`getOctaviusSession`](initialization.md#getting-a-session) already
restates whatever a pool refuses a connection with, and the Spring translator already searches the chain.

## Practical rules and gotchas

* **Log the exception, not its message.** `message` is the `EXCEPTION_NAME:REASON` identifier by design. The diagnostic
  block — details, SQL, parameters, caret — lives in `toString()`, which loggers invoke when the throwable is passed as
  such.

* **One failed statement poisons the whole transaction.** After any error inside an explicit transaction, PostgreSQL
  marks the session aborted (`ReadyForQuery` reports `E`) and rejects every further statement with `25P02` →
  `TransactionStateException(IN_FAILED_TRANSACTION)` until you roll back. That exception is the *consequence*; the
  failure that caused it is the one before it. The commit is refused too, by the driver rather than the server —
  `InvalidOperationException(COMMIT_OF_FAILED_TRANSACTION)` — because the server would carry it out as a rollback and
  report nothing. If a failure is *expected* — a speculative insert, say — isolate it in `session.transaction.nested { }`
  so only its savepoint is discarded. See [Transactions](transactions.md).

* **`queryContext` is nullable, and the driver sets it once.** The first frame to unwind with a null context fills it
  in. Errors raised outside a query — handshake, `commit()`, savepoint misuse — never get one. You may overwrite it
  yourself, which is how a [redacted copy](#query-context) keeps parameter values out of a log entry.

* **`position` indexes the database-level SQL.** For named queries that means the `$n` form, not your `@name` form. The
  two strings sit side by side in the query context precisely so you can line them up.

* **Parser errors have no `dbSql`.** `UNCLOSED_TOKEN` and `MISSING_NAMED_PARAMETER` both fire before the rewrite, so the
  database-level half of the context is empty.

* **Your exceptions don't escape streaming blocks unchanged.** Anything non-Octavius thrown inside a `forEachRow` /
  `forEachObject` / `forEachField` block comes back as `MappingException(BLOCK_FAILED)` with your exception as
  `cause`.

* **A caught exception usually leaves the session alive — `NetworkException` does not.** For errors the server reported,
  and for mapping failures, the protocol is drained to `ReadyForQuery` before the throw, so you can keep using the
  connection. An I/O failure is the opposite case: there is nothing left to drain, `PgStream.isBroken` latches, and
  every later call fails immediately with `CONNECTION_CLOSED` / `CONNECTION_ERROR`. Don't retry on that session — drop
  it and take a fresh one from the pool.

* **Retry concurrency failures, not constraint violations.** `SERIALIZATION_FAILURE` and `DEADLOCK_DETECTED` are timing
  artifacts; a `UNIQUE_CONSTRAINT_VIOLATION` will fail identically forever.

* **`UncategorizedDatabaseException` is a request, not a dead end.** It means a SQLSTATE fell through every branch.
  `sqlState` and `serverErrorMessage` still hold everything the server said — and it is worth reporting so the routing
  table can grow a branch for it.
