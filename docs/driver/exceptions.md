# Error Handling and Exceptions

*A magistrate who rejected a petition did not simply wave the petitioner away. The refusal went into the record with its
grounds attached, the petitioner left knowing exactly which part of his claim had failed, and the court moved on to the
next case without adjourning. Octavius treats a rejected statement the same way: the exception carries what the server
actually said, down to the field it objected to, and a refusal does not close the session it was raised on.*

Octavius builds its error handling around a single base class — `OctaviusException` — that carries rich diagnostic context. Specific subclasses represent distinct categories of failure.

Three things are worth internalizing before the details:

* **The reason enum is a convenience layer, not a lossy one.** Most exceptions carry an enum identifying the exact reason for the failure, so you don't hand-parse `SQLSTATE` codes yourself. But nothing the server said is thrown away: `serverErrorMessage` still holds the complete parsed `ErrorResponse`.
* **A rejected statement does not desynchronize the connection.** When the *server* reports an error, the driver keeps reading the protocol stream until `ReadyForQuery`, *then* throws — so the session is still usable afterwards. The exceptions are the two ways a connection ends: a `NetworkException` means the socket itself is gone, and a `SessionTerminatedException` means the server ended the session — a `FATAL`, which nothing follows but the close. Either way that connection is finished.
* **Which type you catch depends on the door you came in through.** The native session API and Spring's `OctaviusTemplate` throw `OctaviusException` subclasses, the raw JDBC surface throws `SQLExceptionWrapper`. See [Crossing into JDBC and Spring](#crossing-into-jdbc-and-spring).

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

Every failure the driver reports is an `OctaviusException`, and every one carries these:

| Member                 | Type                  | What it holds                                                                                                |
|:-----------------------|:----------------------|:-------------------------------------------------------------------------------------------------------------|
| `message`              | `String`              | The machine-readable identifier, `EXCEPTION_NAME[:REASON_ENUM]` — never prose.                               |
| `sqlState`             | `String?`             | The five-character SQLSTATE. `null` for purely client-side failures that never reached the server.           |
| `serverErrorMessage`   | `ServerErrorMessage?` | The complete parsed `ErrorResponse` from PostgreSQL. `null` when the error originated in the driver.         |
| `queryContext`         | `QueryContext?`       | What *your application* executed — SQL, parameters, and their database-level forms. Attached on the way out. |
| `path`                 | `MutableList<String>` | Where the failure happened, innermost first — see [16](#16-mappingexception). Appended to on the way out.    |
| `cause`                | `Throwable?`          | The underlying exception, where one exists (an `IOException` under a `NetworkException`, for example).       |
| `getDetailedMessage()` | `String?`             | The human-readable explanation, assembled per subclass. The log block shows it under `EXCEPTION DETAILS`.    |

`path` and `queryContext` are the two things a frame can add to an exception **without replacing it**, which is why both
live on the base class. Replacing an exception costs the type the caller catches on — a `ConstraintViolationException`
restated as a mapping failure stops being the thing a retry loop matches — so a layer that knows where it was appends a
segment and rethrows the exception it was given.

`serverErrorMessage` mirrors the server's `ErrorResponse` field for field, so what a subclass does not surface as a
property of its own is still there.

The hierarchy is flat — eighteen concrete subclasses directly under `OctaviusException`, no intermediate layers to reason about.

## From wire to exception

### The server-side path

An `ErrorResponse` is not thrown on arrival. The driver reads on to `ReadyForQuery` first, so the connection is left as
clean as after a successful statement, and only then raises the exception the [routing table](#sqlstate-routing) picks;
the innermost frame that knows the SQL attaches the `queryContext` on the way out, and outer ones leave it be. An I/O
failure halfway through cannot drain anything, and leaves a connection on which every later call fails at once.

### A `FATAL`: the server ending the session

A `FATAL`, or a `PANIC`, in an open session is `SessionTerminatedException`, and the connection is closed with it. The
driver acts on the message rather than on the close that follows, so what reaches you is the reason the server gave. One
that arrived while the connection sat idle — `idle_session_timeout`, `idle_in_transaction_session_timeout`, a
`transaction_timeout` between statements, `pg_terminate_backend()` — is reported by the next statement, which is then
not sent. Where a write fails first because the server ended the session under it, the `FATAL` is thrown in its place
and the failed write rides along as a suppressed exception. At login every refusal is a `FATAL`, and there it is
[`InitializationException`](#4-initializationexception).

> [!NOTE]
> **The reason can still be lost, and then it is a `NetworkException`.** A Windows client discards unread data when a
> reset arrives, so a `FATAL` that lands while a write is under way can be gone; a stock PostgreSQL server on Windows
> sends that reset as a backend exits, so there it can be gone without any write; and on TLS the reason is read only
> after the next write, which Windows may not keep readable. Wherever it does not survive, the connection is still
> reported as gone, never as usable, so code that gives up a session on `SessionTerminatedException` should give it up
> on `NetworkException` too.

### Errors raised during row mapping

Decoding and mapping happen *while* the result stream is being consumed, so a mapping failure is handled the same careful way: the exception is held, the loop keeps draining until `ReadyForQuery`, and only then is it thrown. Two consequences worth knowing:

* If the server *also* reported an error, the server's error wins.
* Exceptions that aren't `OctaviusException` get wrapped in a `MappingException(CONVERSION_ERROR)`. **Exceptions thrown by your own code** inside `forEachRow` / `forEachObject` / `forEachField` blocks are wrapped too, but under their own reason — `MappingException(BLOCK_FAILED)`, with details `"Exception in block: ..."` and the original as `cause`. If you need your own exception type to escape a streaming block, catch and re-throw it outside the block, or make it an `OctaviusException`.

## Message format and logging

`message` always follows a predictable `EXCEPTION_NAME[:REASON_ENUM]` shape. It is an identifier, not a sentence — easy to spot at a glance and easy to filter on programmatically without string-matching gymnastics.

```text
CONSTRAINT_VIOLATION_EXCEPTION:UNIQUE_CONSTRAINT_VIOLATION
STATEMENT_EXCEPTION:SYNTAX_ERROR
CODEC_EXCEPTION:DECODING
PERMISSION_DENIED_EXCEPTION
```

An exception with no reason enum is its name alone. `CodecException` puts its `CodecAction` (`ENCODING` / `DECODING`) in that slot instead.

The human-readable part lives in `toString()`, which renders a structured block: error type, SQL state, the subclass's detailed message, the `path`, the query context, and — if there is one — the cause. Loggers call `toString()` when you pass the exception as a throwable, so **log the exception, not `e.message`**:

```kotlin
logger.error(e) { "Failed to enrol senator" }   // full block
logger.error { e.message }                      // just "CONSTRAINT_VIOLATION_EXCEPTION:..."
```

### Example: a unique violation

```kotlin
session.createNamedQuery("INSERT INTO senators (cognomen) VALUES (@cognomen)")
    .executeUpdate("cognomen" to "Scipio")
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


### Example: a syntax error, with the caret

Where the server or the parameter parser reports a `position`, `StatementException` prints the offending line with a
caret under the character:

```text
Details: syntax error at or near "FRO"
Error at position 10:
SELECT * FRO senators
         ^
```

The caret is drawn against `queryContext.dbSql` where there is one, so for a named query it lands on the `$n` form
PostgreSQL counted in.

## Query context

`QueryContext` is what makes a failure reproducible: the SQL your application wrote and the parameters as you passed
them, next to the statement actually sent (`dbSql`, the `$n` form of a named query) and the values actually bound. A
failure decided before the SQL was rewritten — an `UNCLOSED_TOKEN`, a `MISSING_NAMED_PARAMETER` — has no database-level
form, and one raised outside a query — connection setup, `commit()`, a savepoint — has no context at all, so treat it as
nullable.

Its rendering is bounded, [as in the log](logging.md#parameter-values-are-a-property-not-a-level), but bounded is not
redacted: `logParameterValues=false` governs the driver's own log lines and nothing else, and an exception carries the
values whatever the property says. Where the entry you write yourself must not hold them, replace the context before the
logger renders it. `QueryContext` is a data class, so that is one call:

```kotlin
catch(e: OctaviusException) {
    e.queryContext = e.queryContext?.copy(parameters = emptyMap(), dbParameters = null)
    logger.error(e) { "Census update failed" }
}
```

The statement survives, which is usually what makes the entry worth keeping; only the values go.

## SQLSTATE routing

`ExceptionTranslator` is a single ordered `when` over the SQLSTATE string. Order matters: the first matching branch wins, which is how the specific codes (`40002`, `42501`, `55P03`, `57014`) escape their class-wide defaults.

**Severity comes first.** A `FATAL` or `PANIC` in an open session never reaches the table: whatever its code, the server has [ended the session](#a-fatal-the-server-ending-the-session), so it is `SessionTerminatedException`, with the reason taken from the code where it names one and `UNKNOWN` where it does not. A `FATAL 57P01` is an administrator ending the session, not a server in trouble, and a `FATAL 40001` — a standby ending a session over a recovery conflict — is not a serialization failure to retry on a session that no longer exists. The table is for every `ERROR`. A `FATAL` at login is the server refusing the connection, and is [`InitializationException`](#4-initializationexception) whatever its code.

| #  | SQLSTATE                   | Becomes                                                                |
|:---|:---------------------------|:-----------------------------------------------------------------------|
| 1  | `08*`                      | `NetworkException(CONNECTION_ERROR)`                                   |
| 2  | `22*`                      | `DataException` — reason per code, see [2](#2-dataexception)           |
| 3  | `28*`                      | `InitializationException(SERVER_REJECTED_CREDENTIALS)`                 |
| 4  | `21*`, `0A*`, `3D*`, `3F*` | `StatementException(INVALID_DEFINITION)`                               |
| 5  | `23*`                      | `ConstraintViolationException` — reason per code                       |
| 6  | `25*`                      | `TransactionStateException` — reason per code                          |
| 7  | `40002`                    | `ConstraintViolationException(UNKNOWN)`                                |
| 8  | `40*` (everything else)    | `ConcurrencyException` — `40001`, `40P01`, else `UNKNOWN`              |
| 9  | `42501`                    | `PermissionDeniedException`                                            |
| 10 | `42*` (everything else)    | `StatementException` — reason per code, see [3](#3-statementexception) |
| 11 | `54*`                      | `StatementException(SYNTAX_ERROR)`                                     |
| 12 | `55P03`                    | `ConcurrencyException(LOCK_NOT_AVAILABLE)`                             |
| 13 | `55*` (everything else)    | `DatabaseSystemException`                                              |
| 14 | `57014`                    | `ExecutionAbortedException`                                            |
| 15 | `57*`, `53*`, `58*`, `XX*` | `DatabaseSystemException`                                              |
| 16 | `P0002`, `P0003`, `P0004`  | `RoutineAssertionException` — reason per code                          |
| 17 | `P0*` (everything else)    | `RoutineRaiseException`                                                |
| 18 | anything else              | `UncategorizedDatabaseException`                                       |

## Exception reference

### 1. `ConstraintViolationException`

Unique, foreign-key, check and exclusion violations name the `schema`, `table` and `constraint`; a not-null violation
names the `schema`, `table` and `column`, and no `constraint`. `details` holds the offending key, or for not-null and
check the whole row. Match on `constraint` rather than parsing `dbMessage`.

| Reason (`ConstraintViolationExceptionReason`) | SQLSTATE         | Note                                                                            |
|:----------------------------------------------|:-----------------|:--------------------------------------------------------------------------------|
| `UNIQUE_CONSTRAINT_VIOLATION`                 | `23505`          |                                                                                 |
| `FOREIGN_KEY_VIOLATION`                       | `23503`, `23001` | `23001`: a referenced row deleted or its key changed                            |
| `NOT_NULL_VIOLATION`                          | `23502`          |                                                                                 |
| `CHECK_CONSTRAINT_VIOLATION`                  | `23514`          |                                                                                 |
| `EXCLUSION_CONSTRAINT_VIOLATION`              | `23P01`          |                                                                                 |
| `UNKNOWN`                                     | `23000`, `40002` | Only from a trigger, a procedure or an extension; core PostgreSQL sends neither |

### 2. `DataException`

The SQL is fine and a value is not — usually a parameter the server could not take.

| Reason (`DataExceptionReason`) | SQLSTATE                                             | Note                                                     |
|:-------------------------------|:-----------------------------------------------------|:---------------------------------------------------------|
| `DATA_TRUNCATION`              | `22001`, `22008`, `22015`                            |                                                          |
| `NUMERIC_OUT_OF_RANGE`         | `22003`, `22022`                                     |                                                          |
| `DIVISION_BY_ZERO`             | `22012`                                              |                                                          |
| `INVALID_FORMAT`               | `22007`, `22P02`, `22P03`, `22018`                   |                                                          |
| `ARRAY_SUBSCRIPT_ERROR`        | `2202E`                                              |                                                          |
| `NULL_VALUE_NOT_ALLOWED`       | `22004`, `22002`                                     | Not a `NOT NULL` column, which is a constraint violation |
| `JSON_ERROR`                   | `2203*`                                              |                                                          |
| `XML_ERROR`                    | `2200L`, `2200M`, `2200N`, `2200S`, `2200T`          |                                                          |
| `ESCAPE_CHARACTER_ERROR`       | `22019`, `2200D`, `22025`, `22P06`, `2200C`, `2200B` |                                                          |
| `REGEX_ERROR`                  | `2201B`                                              |                                                          |
| `UNKNOWN`                      | any other `22*`                                      |                                                          |

### 3. `StatementException`

Every reason here points at the SQL, and `position` says where. A call that asked for something the statement cannot
give — a parameter missing from the map, a row count the terminal forbids — is `InvalidOperationException` instead.

| Reason (`StatementExceptionReason`) | SQLSTATE                                                                        | Note                                                                                                        |
|:------------------------------------|:--------------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------------|
| `SYNTAX_ERROR`                      | `42601`, `42602`, `42622`, `42939`, `42000`, `54*`                              | `54*`: a program limit exceeded                                                                             |
| `UNCLOSED_TOKEN`                    | —                                                                               | From the driver's own `@name` scan: a quote, dollar-quoted body or comment left open; `details` names which |
| `UNDEFINED_OBJECT`                  | `42703`, `42883`, `42P01`, `42P02`, `42704`                                     |                                                                                                             |
| `DUPLICATE_OBJECT`                  | `42701`, `42723`, `42P03`, `42P04`, `42P05`, `42P06`, `42P07`, `42712`, `42710` |                                                                                                             |
| `AMBIGUOUS_OBJECT`                  | `42702`, `42725`, `42P08`, `42P09`                                              |                                                                                                             |
| `DATA_TYPE_ERROR`                   | `42804`, `42P18`, `42846`, `42P21`, `42P22`                                     |                                                                                                             |
| `INVALID_DEFINITION`                | `21*`, `0A*`, `3D*`, `3F*`, any other `42*`                                     |                                                                                                             |

### 4. `InitializationException`

No session at all: the driver could not connect or log in, the server refused the login — a `FATAL` before the session
opened, whatever its code — or a `DataSource` would not hand one over.

| Reason (`InitializationExceptionReason`) | When                                                                                                                                                                                                                                                                                                          |
|:-----------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SERVER_REJECTED_CREDENTIALS`            | The server refused the user or password, SQLSTATE class `28`                                                                                                                                                                                                                                                  |
| `UNSUPPORTED_MECHANISM`                  | No mechanism the driver implements, or channel binding was unavailable                                                                                                                                                                                                                                        |
| `UNSUPPORTED_PASSWORD_ENCRYPTION`        | The server asked for MD5, or for a cleartext password outside TLS or with none configured                                                                                                                                                                                                                     |
| `PROTOCOL_VIOLATION`                     | An unexpected message during login, a `NegotiateProtocolVersion` outside 3.0–3.2 among them                                                                                                                                                                                                                   |
| `MISSING_PROTOCOL_PARAMETER`             | Something the login needs was missing: the authentication challenge, a `search_path` a pooler did not pass on, or the `in_hot_standby` that `target_session_attrs` decides by                                                                                                                                 |
| `SSL_ERROR`                              | TLS negotiation failed, or the server does not support it                                                                                                                                                                                                                                                     |
| `UNSUPPORTED_SERVER_VERSION`             | PostgreSQL older than 18, by the `server_version` it reports                                                                                                                                                                                                                                                  |
| `CONNECTION_ERROR`                       | Anything else that kept a connection from opening: the network before login, the server refusing for a reason other than the credentials (`53300`, `3D000`, `57P03`, the code in `sqlState`), a `DataSource` that could not open one, or a list of servers none of which could be used (`details` names each) |
| `CONNECTION_UNAVAILABLE`                 | The data source had none to give — a pool that timed out waiting for a free one; nothing reached the server                                                                                                                                                                                                   |

> [!NOTE]
> **A pool timeout is `CONNECTION_UNAVAILABLE`, not `CONNECTION_ERROR`.** One is the database or the network
> refusing; the other never reached either, and is the application asking for more connections at once than it has.
> It is read from JDBC's `SQLTransientConnectionException`, so any data source reporting a transient failure lands
> on it, not only HikariCP. For a pool, `details` carries its census —
> `(total=1, active=1, idle=0, waiting=0)` — which says whether the pool is simply too small for what the
> application does. [Nesting is one way to make it too small](../client/transactions-failures.md#propagation).

### 5. `NetworkException`

Once this is thrown the connection is finished: every later call on it fails and it counts as closed, which is what
makes a pool evict it rather than hand it on. The SQLSTATE below is the driver's own, for a pool to read; a class-`08`
error from the server arrives as `CONNECTION_ERROR` with the server's code.

| Reason (`NetworkExceptionReason`) | SQLSTATE | Note                                                  |
|:----------------------------------|:---------|:------------------------------------------------------|
| `CONNECTION_ERROR`                | `08006`  | A read or a write failed, or the stream is broken     |
| `CONNECTION_TIMEOUT`              | `08006`  | A read or the connect timed out                       |
| `CONNECTION_CLOSED_BY_PEER`       | `08006`  | The server closed the connection mid-exchange (`EOF`) |
| `CONNECTION_CLOSED`               | `08003`  | A call on a connection that is already closed         |
| `CONNECTION_ABORTED`              | `08000`  | The connection was aborted by the client              |

### 6. `ConcurrencyException`

The one category routinely worth retrying, around the frame that opened the transaction — see [Retrying concurrency
failures](#retrying-concurrency-failures).

| Reason (`ConcurrencyExceptionReason`) | SQLSTATE    | Note                                      |
|:--------------------------------------|:------------|:------------------------------------------|
| `LOCK_NOT_AVAILABLE`                  | `55P03`     | `NOWAIT`, or `lock_timeout`               |
| `DEADLOCK_DETECTED`                   | `40P01`     |                                           |
| `SERIALIZATION_FAILURE`               | `40001`     | Under `REPEATABLE READ` or `SERIALIZABLE` |
| `UNKNOWN`                             | other `40*` | `40002` is a constraint violation         |

### 7. `ExecutionAbortedException`

The server stopped the statement — a `statement_timeout`, or `session.cancelQuery()` — and the session lives on: the
next statement runs normally, and a transaction is doomed as after any other error. There is one way to be cancelled, so
no reason enum; `sqlState` is `57014`.

### 8. `SessionTerminatedException`

Any `FATAL` or `PANIC` once the session is open, whatever its code — see [A `FATAL`: the server ending the
session](#a-fatal-the-server-ending-the-session). The session goes with it: what the transaction had not committed is
gone, the next statement gets a `NetworkException` naming the server's reason in `details` and every one after that
`CONNECTION_CLOSED`, so a retry starts on a new session — around the frame that borrows it, not around a `required { }`
on the same one.

| Reason (`SessionTerminatedExceptionReason`) | SQLSTATE          | Note                                                            |
|:--------------------------------------------|:------------------|:----------------------------------------------------------------|
| `TRANSACTION_TIMEOUT`                       | `25P04`, `25P03`  | `transaction_timeout`, or `idle_in_transaction_session_timeout` |
| `IDLE_SESSION_TIMEOUT`                      | `57P05`           | `idle_session_timeout`                                          |
| `ADMINISTRATOR_COMMAND`                     | `57P01`           | `pg_terminate_backend()`, or the server shutting down           |
| `UNKNOWN`                                   | any other `FATAL` | A recovery conflict on a standby, among others                  |

### 9. `TransactionStateException`

The statement is fine and the transaction it arrived in is not, which is why this is not a `StatementException`: the
server refuses before looking at the SQL, so there is no `position`. `IN_FAILED_TRANSACTION` is a consequence, and the
failure worth reading is the earlier one — see [Transaction state](transactions.md#transaction-state).

| Reason (`TransactionStateExceptionReason`) | SQLSTATE    | Note                                                                                                     |
|:-------------------------------------------|:------------|:---------------------------------------------------------------------------------------------------------|
| `IN_FAILED_TRANSACTION`                    | `25P02`     | An earlier error doomed the transaction                                                                  |
| `READ_ONLY_TRANSACTION`                    | `25006`     |                                                                                                          |
| `NO_ACTIVE_TRANSACTION`                    | `25P01`     |                                                                                                          |
| `ACTIVE_TRANSACTION`                       | `25001`     |                                                                                                          |
| `UNKNOWN`                                  | other `25*` | Two-phase commit among them; `25P03` and `25P04` end the session, see [8](#8-sessionterminatedexception) |

### 10. `RoutineRaiseException`

`RAISE EXCEPTION` in PL/pgSQL (`P0001`), and any other `P0*` that is not an assertion. There is no reason enum because
there is nothing to tell apart: `P0000` is reachable only by asking for it with
`RAISE … USING ERRCODE = 'plpgsql_error'`, and `sqlState` says which.

> [!TIP]
> `RAISE EXCEPTION` accepts `USING ERRCODE = '...'`. Raise a domain-specific SQLSTATE and it routes through the normal
> table — `ERRCODE = '23505'` will reach your application as a `ConstraintViolationException`, not as this.

### 11. `RoutineAssertionException`

Separate from `RoutineRaiseException` because a `RAISE` is the routine deciding, while these are a claim the data
falsified — a defect in the database code, as a `fetch*Strict` finding no row is one in Kotlin.

| Reason (`RoutineAssertionExceptionReason`) | SQLSTATE | Note                                     |
|:-------------------------------------------|:---------|:-----------------------------------------|
| `NO_DATA_FOUND`                            | `P0002`  | `SELECT INTO STRICT` found no row        |
| `TOO_MANY_ROWS`                            | `P0003`  | `SELECT INTO STRICT` found more than one |
| `ASSERT_FAILURE`                           | `P0004`  | An `ASSERT` did not hold                 |

### 12. `PermissionDeniedException`

`42501`, with no reason enum. Core PostgreSQL names the refused object only in `dbMessage` —
`permission denied for table senators` — and leaves `schema`, `table`, `column` and `datatype` `null`; `routine` is the
server's own C function that refused, not one of yours.

### 13. `InvalidOperationException`

Raised by the driver itself, so it has no `sqlState` and no `serverErrorMessage`; inside a query it still carries the
`queryContext`.

| Reason (`InvalidOperationExceptionReason`) | When                                                                                                                                                                                                                                                                                                |
|:-------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AUTO_COMMIT_VIOLATION`                    | `commit()`, `rollback()` or a savepoint while auto-commit is on                                                                                                                                                                                                                                     |
| `COMMIT_OF_FAILED_TRANSACTION`             | `commit()`, or auto-commit switched back on, in a transaction an earlier error aborted; nothing is sent, see [Transaction state](transactions.md#transaction-state)                                                                                                                                 |
| `INVALID_SAVEPOINT`                        | A savepoint the driver did not create, a `null` name, or the id asked of a named savepoint or the name of an unnamed one                                                                                                                                                                            |
| `RESOURCE_CLOSED`                          | The session, statement, Large Object or `COPY` handle is closed; `details` names it, and the answer is a new one                                                                                                                                                                                    |
| `INVALID_ARGUMENT`                         | An argument the call does not accept; `details` names the value                                                                                                                                                                                                                                     |
| `MISSING_NAMED_PARAMETER`                  | A `@name` in the SQL with no value in the map                                                                                                                                                                                                                                                       |
| `UNWRAP_ERROR`                             | `unwrap()` to an interface this object does not implement                                                                                                                                                                                                                                           |
| `FEATURE_NOT_SUPPORTED`                    | A JDBC feature the driver deliberately does not implement                                                                                                                                                                                                                                           |
| `UNEXPECTED_RESULT`                        | `execute()` or `executeUpdate()` received rows — use a `fetch*`, or `execute(ignoreRows = true)`; also a `COPY` that did not start                                                                                                                                                                  |
| `INCORRECT_RESULT_SIZE`                    | A `fetch*Strict` found no row, or a single-row fetch found two                                                                                                                                                                                                                                      |
| `CONNECTION_BUSY`                          | The connection is still carrying an exchange — an open `COPY`, or a statement still being read, usually from a `forEach` block or a converter; also a listener loop started inside a transaction. See [COPY](copy.md), [Queries](queries.md) and [Listen / Notify](listen-notify.md#listener-loops) |

### 14. `TypeException`

| Reason (`TypeExceptionReason`) | When                                                                                                              |
|:-------------------------------|:------------------------------------------------------------------------------------------------------------------|
| `TYPE_NOT_FOUND`               | The type is not in the catalog — often a `CREATE TYPE` after it was loaded, which `session.reloadTypes()` fixes   |
| `NOT_A_CONTAINER`              | The OID is not the composite, record, range or multirange the call needs                                          |
| `MISSING_CODEC`                | The driver has no codec for that OID — see [Type System](type-system.md#base-types-the-driver-does-not-implement) |

### 15. `CodecException`

Whatever a codec throws arrives as this, the codec's own failure as the `cause` and `action` saying whether it was
`ENCODING` or `DECODING`. On `DECODING`, `value` keeps at most the first 100 bytes; on `ENCODING` it is the value as it
was, and only its rendering is shortened — a `ByteArray` shows as `ByteArray(n bytes)`, a longer text is cut at 100
characters.

### 16. `MappingException`

Raised wherever a value is converted, on either side of the wire, and `path` says where. It is printed outermost first —
`PATH: consul -> province -> founded` — from the column, whichever route reached it, down to a container's own accessor;
a record's fields and an array's elements give a position (`[0]`), a range's bounds `lower` and `upper`. Inside a
[transaction plan](../client/plans.md) the plan's segments come first:
`PATH: step 3 -> parameter 'amount' -> map #1 -> founded`. `COLUMN_NOT_FOUND` adds no segment, a lookup that found
nothing not being a location; the name asked for is in the message.

| Reason (`MappingExceptionReason`) | When                                                                                                             |
|:----------------------------------|:-----------------------------------------------------------------------------------------------------------------|
| `COLUMN_NOT_FOUND`                | No column, composite attribute, record field or array element by that name or at that position                   |
| `REQUIRED_ATTRIBUTE_MISSING`      | `NULL`, or nothing, for a non-nullable Kotlin property                                                           |
| `NO_CONVERTER_FOUND`              | No converter for that source and target                                                                          |
| `CONVERSION_ERROR`                | A conversion failed, or a converter returned a type other than the one asked for                                 |
| `BLOCK_FAILED`                    | The block given to `forEachRow`, `forEachObject` or `forEachField` threw something of its own; it is the `cause` |

### 17. `DatabaseSystemException`

The engine in trouble rather than the query wrong — out of memory, disk full, a configuration limit, an internal error:
`53*`, `55*` but `55P03`, `57*` but `57014`, `58*` and `XX*`, as an `ERROR`. `details` holds the SQLSTATE and the
server's message, and there is no reason enum.

### 18. `UncategorizedDatabaseException`

A SQLSTATE no branch of the routing table claims — worth reporting, so the table can grow one. `sqlState` says which,
`serverErrorMessage` holds everything the server said, and there is no reason enum.

## Catching at the right altitude

Catch the category where the class alone decides what to do, and branch on `reason` inside it where the distinction
matters, with no SQLSTATE strings involved:

```kotlin
try {
    session.createNamedQuery("INSERT INTO senators (cognomen, province_id) VALUES (@cognomen, @province)")
        .executeUpdate("cognomen" to cognomen, "province" to provinceId)
} catch (e: ConstraintViolationException) {
    when (e.reason) {
        ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION -> throw SenatorAlreadyEnrolled(e.constraint, e)
        ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION       -> throw UnknownProvince(provinceId, e)
        else                                                           -> throw e
    }
}
```

For a global handler or a log, catch `OctaviusException` and log the exception itself rather than its `message` — see
[Message format and logging](#message-format-and-logging).

### Retrying concurrency failures

`SERIALIZATION_FAILURE` and `DEADLOCK_DETECTED` mean "nothing was wrong with your statement, the timing was unlucky" — the same work may well succeed on a second attempt.

Such an error dooms the transaction, and the `ROLLBACK` is sent by whoever owns its boundary —
`transaction.required { }` if it opened the transaction, `session.rollback()` under `autoCommit = false`. So a retry
restarts the whole transaction, which is also what gets it a new snapshot under `REPEATABLE READ` / `SERIALIZABLE`, and
the wrapper goes around the frame that owns the boundary:

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
            .executeUpdate("amount" to 100, "id" to 1)
        createNamedQuery("SELECT name FROM consuls WHERE id = @id").fetchFieldStrict<String>("id" to 1)
    }
}
```

Wrapping a `required { }` that merely *joined* an outer transaction retries nothing useful — the boundary is somewhere further out, so each attempt replays into the same doomed transaction. Put the retry where the transaction actually begins.

## Crossing into JDBC and Spring

The exception you catch depends on which door you came in through.

| Entry point                                                                         | You catch                                                                                                                                                                                  |
|:------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `OctaviusSession` — `createNativeQuery`, `createNamedQuery`, `commit`, `rollback` … | `OctaviusException` subclasses, unchanged                                                                                                                                                  |
| A raw `java.sql.Connection` (`dataSource.connection`)                               | `SQLExceptionWrapper` (a `java.sql.SQLException`), or a bare `InvalidOperationException` from a method the driver [does not implement](octavius-vs-jdbc.md#what-is-explicitly-unsupported) |
| Spring's `OctaviusTemplate` and `OctaviusJdbcTransactionManager`                    | `OctaviusException` subclasses, unchanged — see [Spring Integration](spring-integration.md#exceptions)                                                                                     |
| `HikariDataSource(config)` — building the pool itself                               | `HikariPool.PoolInitializationException`, Hikari's own — see [Initialization](initialization.md#adding-hikaricp-connection-pooling)                                                        |

### `SQLExceptionWrapper`

The JDBC surface must throw `SQLException`, since a pool reads the SQLSTATE off it to decide what to evict, so the
connection and the `Statement` it hands out wrap the driver's exception. The wrapper carries the `message` and the
SQLSTATE and nothing else — `cause` is `null`, and it has no stack trace of its own — and the typed exception is
`wrapper.wrappedException`.

A `SessionTerminatedException` keeps the server's SQLSTATE, and `25P04` or `57P05` is not one a pool reads as a lost
connection, so that wrapper chains a next exception with `08006`: the first says why, the next that the connection is
gone, and HikariCP evicts it at once rather than at its next validation.

**Session API users never see the wrapper.** The session's operations that go through the JDBC connection —
`autoCommit`, `commit()`, `rollback()`, `networkTimeout` and the rest — throw the driver's exception found in the chain,
and a bare `SQLException` from a pool's proxy, over a connection it already evicted, as
`NetworkException(CONNECTION_ERROR)` with it as the `cause`.

## Practical rules and gotchas

* **A timeout is two different failures.** `statement_timeout` cancels a statement:
  `ExecutionAbortedException`, and the session goes on. `transaction_timeout`,
  `idle_in_transaction_session_timeout` and `idle_session_timeout` end the session:
  `SessionTerminatedException`, and the connection is gone. Code that retries a timed-out statement on the session it
  holds is right for the first and wrong for the rest.
