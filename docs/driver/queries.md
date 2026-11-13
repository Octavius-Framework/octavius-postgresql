# Queries

*Rome's oldest civil procedure demanded exact words and prescribed gestures: a claimant who said "vines" where the
statute said "trees" lost his case on the spot, having been right about everything except the ritual. It was eventually
replaced by the formula — one written statement of what was claimed and what was to be decided. A query here is a
formula, not a ritual: say what you are asking and what shape you want it back in, and it is done in one call.*

A query is built from a session and executed in one call. There is no statement object to close and no `ResultSet` to
walk: you choose how the parameters go in and what shape comes back.

Contents:
* [Two ways to pass parameters](#two-ways-to-pass-parameters)
* [Choosing a fetch method](#choosing-a-fetch-method)
* [Reading a `Row`](#reading-a-row)
* [Nullability and the Strict variants](#nullability-and-the-strict-variants)
* [Streaming large results](#streaming-large-results)
* [Do not re-enter the session while rows are being read](#do-not-re-enter-the-session-while-rows-are-being-read)
* [Statements that return nothing](#statements-that-return-nothing)
* [Functions and procedures](#functions-and-procedures)
* [Cancelling a query in flight](#cancelling-a-query-in-flight)
* [Per-query converters](#per-query-converters)

## Two ways to pass parameters

### Positional — `createNativeQuery`

PostgreSQL's native placeholders, `$1`, `$2`, `$3`, passed as a `vararg`:

```kotlin
session.createNativeQuery("SELECT id, cognomen FROM senators WHERE province_id = $1 AND active = $2")
    .fetchRows(7, true)
```

### Named — `createNamedQuery`

Named placeholders take their values as `Pair`s or as a `Map`, so the order of the arguments stops mattering:

```kotlin
session.createNamedQuery("SELECT id, cognomen FROM senators WHERE province_id = @province AND active = @active")
    .fetchRows("province" to 7, "active" to true)

session.createNamedQuery("... WHERE province_id = @province")
    .fetchRows(mapOf("province" to 7))
```

Names are rewritten to `$n` before the query is sent, and a name repeated in the statement becomes one parameter:
`WHERE created > @date OR updated > @date` sends `date` once.

The rewriter leaves an `@` alone inside a string literal, a quoted identifier, a line or block comment, or a `$$ … $$`
block. PostgreSQL's `@`-operators are safe too, since a parameter name cannot start with an operator character:

```kotlin
// Both @-operators survive; only @filter and @q are parameters
session.createNamedQuery("SELECT * FROM t WHERE data @> @filter AND tsv @@ to_tsquery(@q)")
```

What is not safe is an `@` written straight against a name, because that is what a parameter looks like: PostgreSQL
reads `@x` as the absolute value of `x` and `a <@b` as `a <@ b`, and here both are parameters. Put a space after the
`@`, `@ x` and `a <@ b`, and PostgreSQL reads them as it would anywhere else.

### Quoting a name that comes from outside

A placeholder is a value, so a table or column chosen at runtime has to be interpolated into the SQL text. Where the
name cannot be mapped onto one you wrote yourself, `quoteAsPgIdentifier()` quotes it:

```kotlin
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier

val table = tenantTable.quoteAsPgIdentifier()   // legio X  ->  "legio X"
session.createNativeQuery("SELECT count(*) FROM $table").fetchFieldStrict<Long>()
```

It wraps the name in double quotes and doubles every quote inside it; a NUL character, which an identifier cannot hold,
throws `StatementException(SYNTAX_ERROR)`. The driver quotes `LISTEN` and `UNLISTEN` channels and savepoint names the
same way, so those take arbitrary strings. It always quotes, which makes the name case-sensitive:
`CREATE TABLE MixedCase` stores `mixedcase`, and quoting the string `MixedCase` matches nothing.

## Choosing a fetch method

A `fetch*` method is for a statement that produces rows: a `SELECT`, or an `INSERT` / `UPDATE` / `DELETE` with
`RETURNING`. The families differ only in what they hand back:

| You want                 | All rows            | One row                | Exactly one row             | Streamed          |
|:-------------------------|:--------------------|:-----------------------|:----------------------------|:------------------|
| **Raw columns** (`Row`)  | `fetchRows()`       | `fetchRow(): Row?`     | `fetchRowStrict(): Row`     | `forEachRow()`    |
| **Mapped to your class** | `fetchObjects<T>()` | `fetchObject<T>(): T?` | `fetchObjectStrict<T>(): T` | `forEachObject()` |
| **First column only**    | `fetchFields<T>()`  | `fetchField<T>(): T`   | `fetchFieldStrict<T>(): T`  | `forEachField()`  |

```kotlin
// Raw columns - pull what you need out of the Row
val row = session.createNativeQuery("SELECT id, cognomen FROM senators WHERE id = $1").fetchRowStrict(1)
val cognomen: String = row.get("cognomen")

// Mapped straight onto a Kotlin class
val senators: List<Senator> = session.createNativeQuery("SELECT * FROM senators").fetchObjects()

// A scalar - counting the legions
val total: Long = session.createNativeQuery("SELECT count(*) FROM legions").fetchFieldStrict()
```

How columns find their way onto constructor parameters is in [Type System](type-system.md), and a query can carry
[converters of its own](#per-query-converters).

## Reading a `Row`

`Row` is decoded up front, not a cursor. Every column is decoded when the row is built, so nothing is read off the
connection afterwards and a row can be kept, passed to another thread, or read in any order.

What is *not* finished at that point is the conversion: `row.get<T>()` resolves a converter when you call it, against
the catalog the row's execution pinned, so it answers the same whenever it is called, whatever has been registered
since. See [Concurrency](concurrency.md#what-can-cross-a-thread-boundary).

Values come out through `get`, by name or by zero-based index:

```kotlin
val cognomen: String = row.get("cognomen")
val id: Int          = row.get(0)
val profile: Senator = row.get("profile")   // any type a converter can produce
```

The whole row comes out through `toObject`, mapped onto `T` the way `fetchObject<T>()` maps a row of a result, so a row
already in hand, from `fetchRows()` or `forEachRow()`, can still become a class or a `Map`:

```kotlin
val senator: Senator           = row.toObject()
val columns: Map<String, Any?> = row.toObject()
```

Both have a twin taking a `KType`, `get<T>(index, targetType)` and `toObject<T>(targetType)`, which is the form
reachable from Java.

The rest of the surface is metadata, for when the shape of the result is not known in advance:

| Member                 | Gives                                                                              |
|:-----------------------|:-----------------------------------------------------------------------------------|
| `columnNames`          | Every column name, in order.                                                       |
| `getColumnIndex(name)` | The index for a name, or `MappingException(COLUMN_NOT_FOUND)`.                     |
| `hasColumn(name)`      | Whether a column carries that name — what `getColumnIndex` answers by raising.     |
| `getRaw(index)`        | The decoded value *before* conversion — `Int`, `String`, `PgComposite`, `PgArray`. |
| `getOid(index)`        | The PostgreSQL OID of that column's type.                                          |
| `metadata`             | `RowMetadata` — what the result says about its own columns.                        |

`hasColumn` is for a result whose shape is not fixed, such as a projection assembled at runtime or a `RETURNING` that
differs by branch. A `try`/`catch` around a lookup would also catch the mapping failures you did want to hear about.

Asking one row for two different shapes costs one decode and two conversions:

```kotlin
val asMap: Map<String, Any?> = row.get(0)
val asClass: Senator = row.get(0)     // same bytes, not decoded again
```

> [!NOTE]
> **A duplicated column name resolves to the first one.** `SELECT s.id, p.id FROM senators s JOIN provinces p …`
> produces two columns called `id`, and `row.get<Int>("id")` returns the first without complaining, as JDBC's
> `findColumn` does. Alias the columns in the SQL (`p.id AS province_id`) when you need both, or read them by index.

### What a column says about itself

`row.metadata` is resolved once per result and shared by all its rows. `getColumn(index)` and `getColumn(name)` hand
back a `ColumnMetadata`:

| Member         | Gives                                                                               |
|:---------------|:------------------------------------------------------------------------------------|
| `name`         | The name the column came back under — the alias, where the query gave it one.       |
| `type`         | The `PgType` it was read as, resolved against the catalog the query ran under.      |
| `oid`          | That type's OID.                                                                    |
| `typeModifier` | The server's raw `atttypmod`, `-1` where the type takes none.                       |
| `origin`       | The relation and column it was read from, or `null` where it was not read from one. |

`origin` is what lets a result say more about a column than the query called it:

```kotlin
val column = row.metadata.getColumn("name")   // SELECT s.cognomen AS name FROM senators s
column.name                  // "name"      - what the query called it
column.origin?.columnName    // "cognomen"  - what the table calls it
column.origin?.relationName  // "senators"
```

The names come from the type catalog the driver already holds, so they cost no extra query. Three states are worth
telling apart:

* **`origin == null`** - the column is not a reference to a stored column at all: an expression, a literal, the
  output of a function.
* **`origin` present, its names `null`** - the server tracked the value back to a relation the type catalog does
  not describe. That means `pg_catalog`, `information_schema` and temporary tables, which the type load skips,
  and anything created since the last load, which `reloadTypes()` fills in.
* **`origin` present and named** - the ordinary case.

`typeModifier` is left as the server sends it, since reading one depends on the type: a `varchar(64)` arrives as `68`,
a `timestamp(3)` as `3`, and a `numeric(10,2)` as `((10 shl 16) or 2) + 4`.

A column whose type the catalog does not describe fails when the result is described, not when a value is read from
it, so it fails the same way every time.

> [!NOTE]
> **A domain column is described by the type underneath it.** PostgreSQL resolves a domain to its base type before
> describing a column, so `type` is never a `PgType.Domain`: a column declared over a domain of `numeric` arrives as
> `numeric`, and only `origin` still says which column it was. Inside an array or a composite, converters see a domain
> [the same way](type-system.md#how-a-converter-gets-chosen).

## Nullability and the Strict variants

Two things can be missing here, and different halves of the API handle them. The `Strict` suffix governs **how many
rows came back**; how you type `T` governs **whether a value was there at all**. For the field family:

| Situation                                  | `fetchField<T>()`                                  | `fetchFieldStrict<T>()`                            |
|:-------------------------------------------|:---------------------------------------------------|:---------------------------------------------------|
| No rows, `T` nullable                      | `null`                                             | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| No rows, `T` not nullable                  | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| More than one row                          | `InvalidOperationException(INCORRECT_RESULT_SIZE)` | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| One row, value is `NULL`, `T` nullable     | `null`                                             | `null`                                             |
| One row, value is `NULL`, `T` not nullable | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     |

**`T`'s nullability covers both ways a value can be absent, and treats them the same.** A row that never matched and a
row carrying SQL `NULL` give no `String` either way, so both raise `REQUIRED_ATTRIBUTE_MISSING` for a non-nullable
`T`. If the lookup may find nothing, say so: `fetchField<String?>()`, and the same for `fetchFields<String?>()`. The
`Strict` suffix counts rows and nothing else, so `fetchFieldStrict<String?>()` still refuses an empty result with
`INCORRECT_RESULT_SIZE`.

**Only the field family works this way.** `fetchObject<T : Any>` bounds `T` to a non-null type and `fetchRow` returns
a plain `Row?`, so both return `null` when nothing matched; `fetchObjectStrict` and `fetchRowStrict` demand a row.
`fetchField<T>` leaves `T` unbounded on purpose, so that `T` says whether a value is required, and it returns `T`
rather than `T?`, so a non-nullable one needs no unwrapping:

```kotlin
val cognomen: String  = session.createNativeQuery("SELECT cognomen FROM senators WHERE id = $1").fetchField(7)
val patron: String?   = session.createNativeQuery("SELECT patron FROM senators WHERE id = $1").fetchField<String?>(7)
```

`Row.get<T>()`, `fetchFields<T>(): List<T>` and `fetchFieldStrict<T>(): T` read `T` the same way: you never receive a
`null` you did not ask for.

The one-row methods ask the server for at most two rows, so a query that accidentally matches a million does not drag
them across the wire before failing.

## Streaming large results

`fetchRows()` and friends materialize everything into memory. For a result that will not comfortably fit, such as an
audit of every citizen in the census, the `forEach*` methods pull rows in batches and hand them to your block one at a
time. `fetchSize` is the batch size and has no default, so you always state it:

```kotlin
session.createNativeQuery("SELECT * FROM citizens WHERE province_id = $1")
    .forEachObject<Citizen>(7, fetchSize = 500) { citizen ->
        writeToExportFile(citizen)
    }
```

No row is kept once your block is done with it, so memory stays flat however many the query returns. `fetchSize` is how
many rows the server sends before it waits to be asked for the next batch.

**`fetchSize = 0` asks for the whole result at once**, in one round trip rather than one per batch. That suits a loop
whose point is to *fold* a result, deduplicating it, merging adjacent rows or running a total, rather than to survive
its size: `fetchRows()` would build the whole `List` first, and batching buys nothing when every row is read anyway. A
negative `fetchSize` is refused with `InvalidOperationException(INVALID_ARGUMENT)`.

**The whole iteration is one statement, for as long as it takes.** The server treats the loop as a single running
statement from the first batch to the last, and whatever your block does happens inside that statement's lifetime.

> [!WARNING]
> **`statement_timeout` covers the entire loop, including time spent in your block.** A loop that outlasts it ends in
> `ExecutionAbortedException`; the session stays usable, but the rest of the rows are gone. If a slow block is
> unavoidable, raise the timeout for that session, or collect the rows first and do the slow work afterwards.

**The session is busy for the duration.** Your block runs while the driver is still reading the result, so it must not
touch that same session; see [below](#do-not-re-enter-the-session-while-rows-are-being-read).

**An exception from your block is wrapped.** Anything that is not an `OctaviusException` arrives as
`MappingException(BLOCK_FAILED)` with your exception as its `cause`, and the wrapper carries the query context. See
[Error Handling](exceptions.md#errors-raised-during-row-mapping) if you need your own exception type to survive.

## Do not re-enter the session while rows are being read

Most `fetch*` methods convert each value as it arrives, before the exchange with the server is finished. Code of yours
that runs at that moment, a `forEach*` block or a custom `ResultConverter`, runs inside an unfinished exchange, and a
query on the same session from there collides with it. Streaming has nothing to do with it; what matters is only
whether your code runs during the read:

| Where your code runs                                 | Querying the same session from there |
|:-----------------------------------------------------|:-------------------------------------|
| `forEach*` block                                     | Collides                             |
| `ResultConverter` under any mapping fetch            | Collides                             |
| After `fetchRows()`, calling `row.get<T>()` yourself | Fine — the exchange is already over  |

The driver refuses such a call **before anything reaches the wire**: you get
`InvalidOperationException(CONNECTION_BUSY)`, or that exception as the `cause` of a `MappingException(CONVERSION_ERROR)`
when it came from a converter, whose `path` names the column being mapped. Nothing is corrupted and the next
statement works normally; only the operation you interrupted is lost. The same guard covers a `COPY` started from that
position.

If code in that position needs the database, give it a second session.

## Statements that return nothing

- **`executeUpdate()`**: DML that changes rows (`INSERT`, `UPDATE`, `DELETE`), sent through the Extended Query Protocol
  with its parameters bound. Returns the affected row count as a `Long`.
- **`execute()`**: DDL, `SET` and administrative commands, sent through the Simple Query Protocol. It **cannot bind
  parameters** and returns neither rows nor a count.

```kotlin
val promoted = session.createNativeQuery("UPDATE senators SET rank = $1 WHERE province_id = $2")
    .executeUpdate("consul", 7)

session.createNativeQuery("CREATE INDEX idx_senators_province ON senators (province_id)").execute()
```

A row-returning statement handed to `execute()` throws `InvalidOperationException(UNEXPECTED_RESULT)` rather than
being dropped in silence. Where the SQL is a script written elsewhere and a `SELECT` in it is meant,
`execute(ignoreRows = true)` drops the rows without raising.

> [!IMPORTANT]
> An `INSERT` or `UPDATE` with a `RETURNING` clause returns rows, so it belongs to the `fetch*` family, not
> `executeUpdate()`. Getting a generated id back is `fetchFieldStrict<Long>()`:
>
> ```kotlin
> val newId: Long = session.createNativeQuery("INSERT INTO senators (cognomen) VALUES ($1) RETURNING id")
>     .fetchFieldStrict("Cato")
> ```

### `execute()` takes a whole script

The Simple Query Protocol takes several statements at once, so `execute()` accepts a script separated by `;` and sends
it in a single round trip:

```kotlin
session.createNativeQuery("""
    CREATE TABLE castra (id serial PRIMARY KEY, nomen text NOT NULL);
    CREATE INDEX idx_castra_nomen ON castra (nomen);
    INSERT INTO castra (nomen) VALUES ('Vindobona')
""").execute()
```

PostgreSQL runs such a script in an implicit transaction, so a statement failing halfway takes the ones before it down
with it. Inside `transaction.required { }` it joins the transaction already open instead. The ban on rows covers every
statement in the script: one stray `SELECT` and the call is an `InvalidOperationException(UNEXPECTED_RESULT)`, unless
it was `execute(ignoreRows = true)`. A script from `pg_dump` is the usual reason for the flag, since it emits
`SELECT pg_catalog.setval(...)` for every sequence it carries.

The `fetch*` family and `executeUpdate()` go through the Extended Query Protocol, which takes one statement, so
`SELECT 1; SELECT 2` there is `StatementException(SYNTAX_ERROR)`. A script cannot bind anything: a `$1` inside one is
`StatementException(UNDEFINED_OBJECT)`. Whatever varies belongs in a statement of its own, run through
`executeUpdate()`.

## Functions and procedures

A routine is called with ordinary SQL and read with an ordinary terminal; nothing is registered as an output before the
call, as a JDBC `CallableStatement` needs. A function goes through `SELECT` and a procedure through `CALL`, and their
`OUT` and `INOUT` parameters come back as the columns of the row the call returns, named after the parameters. `CALL`
wants a value in every argument position, so an `OUT` one takes a `NULL` in the SQL:

```kotlin
// CREATE FUNCTION province_census(uid int, OUT population int, OUT taxes numeric)
val census = session.createNativeQuery("SELECT * FROM province_census($1)").fetchRowStrict(3)
val taxes: BigDecimal = census.get("taxes")

// CREATE PROCEDURE count_legion(legion_id int, OUT strength int)
val strength = session.createNativeQuery("CALL count_legion($1, NULL)").fetchFieldStrict<Int>(7)
```

A procedure with no `OUT` parameter returns no row and runs through `executeUpdate()`. A function returning `void`
returns one row with one `void` column, read as `Unit`. A `LANGUAGE sql` function puts `NULL` in it where PL/pgSQL
and the built-ins put a value, so `fetchField<Unit?>()` reads either.

PostgreSQL picks a function's overload by the types of its arguments, and a value goes out under the type its Kotlin
class maps to, so the class is what picks it. A `Double` passed to a function taking `numeric` finds no function at
all, which is `StatementException(UNDEFINED_OBJECT)`: pass a `BigDecimal`. Declaring the `Double` with
`withPgType("numeric")` does not convert it and raises `MappingException(NO_CONVERTER_FOUND)`.

A `null` has no class to go by, so it goes out with no type and leaves the choice to the server. Between `int` and
`bigint` the server refuses with `StatementException(AMBIGUOUS_OBJECT)`, and between `int` and `text` it takes
`text`. A `null` declared with `withPgType` names the overload meant:

```kotlin
// CREATE FUNCTION levy(legion_id int, amount int) RETURNS text, and the same with amount bigint
session.createNativeQuery("SELECT levy($1, $2)").fetchField<String>(7, null.withPgType("int8"))
```

## Cancelling a query in flight

`session.cancelQuery()` asks the server to abandon whatever that session is currently running. It has to be called
**from a different thread**, since the one that issued the query is blocked inside it. The request does not wait behind
the running statement: it travels over a separate short-lived connection.

That connection is negotiated under the session's own [`sslmode`](initialization.md#ssl), so the cancel key it carries
is encrypted wherever the session is. Under `REQUIRE` or stronger, a server that will not encrypt means the request is
*not sent* rather than sent in plaintext. Its connect and its reads are bounded by
[`cancelSignalTimeout`](initialization.md#network-and-limits), 10 seconds by default. `cancelQuery()` returns once the
server has closed that connection, which is how it acknowledges having read the request.

```kotlin
val work = OctaviusDispatchers.VirtualExecutor.submit {
    session.createNativeQuery("SELECT count(*) FROM census_of_every_citizen").fetchFieldStrict<Long>()
}

// Elsewhere, once it has gone on long enough
session.cancelQuery()
```

The blocked call then fails with `ExecutionAbortedException` (SQLSTATE `57014`), the session stays usable, and the
next statement runs normally.

Two things it does not promise. **A cancel that fails is not reported**: if the request cannot be delivered, the
failure is swallowed and the query keeps running, so a return means "asked", not "stopped".

And **it can hit the wrong statement.** The request names the session's backend, not a statement, and the server
abandons whatever that backend is running when the signal is handled. If the statement you meant finished first and
another has started on that connection, the cancel takes that one. On a session only one thread touches, the statement
is still running while you cancel it; where several threads share a session, or a cancel is fired at a session about to
go back to the pool, it may not be. Cancel a statement you know is still running, and let the thread that issued it see
the outcome before the session runs anything else.

For a limit on every statement, set `statement_timeout` as a [startup parameter](initialization.md#startup-parameters).
It needs no second thread and no second connection, covers the whole session, and cannot land on the wrong statement.

## Per-query converters

Conversion normally goes through the session's type manager. When one query needs different treatment, such as a
column parsed into a domain type or a DTO serialized a particular way, register a converter on that query alone:

- `registerResultConverter(converter)`: database value to Kotlin object.
- `registerParameterConverter(converter)`: Kotlin object to query parameter.

Both return the query, so they chain onto the call:

```kotlin
session.createNativeQuery("SELECT * FROM tabulae WHERE id = $1")
    .registerResultConverter(RomanNumeralConverter())
    .fetchObjectStrict<Tabula>(1)
```

A converter registered here is checked before the session's and is invisible to every other query.
[Type System](type-system.md) covers registering converters for the whole session instead.
