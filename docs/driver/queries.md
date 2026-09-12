# Queries

*Rome's oldest civil procedure demanded exact words and prescribed gestures: a claimant who said "vines" where the
statute said "trees" lost his case on the spot, having been right about everything except the ritual. It was eventually
replaced by the formula — one written statement of what was claimed and what was to be decided. A query here is a
formula, not a ritual: say what you are asking and what shape you want it back in, and it is done in one call.*

Every query in Octavius is built from a session and executed in one call. There is no statement object to close, no `ResultSet` to walk, and no lifecycle to get wrong — you choose how parameters go in, and what shape you want back.

Contents:
* [Two ways to pass parameters](#two-ways-to-pass-parameters)
* [Choosing a fetch method](#choosing-a-fetch-method)
* [Reading a `Row`](#reading-a-row)
* [Nullability and the Strict variants](#nullability-and-the-strict-variants)
* [Streaming large results](#streaming-large-results)
* [Do not re-enter the session while rows are being read](#do-not-re-enter-the-session-while-rows-are-being-read)
* [Statements that return nothing](#statements-that-return-nothing)
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

Named placeholders read better past a couple of arguments, and they remove the classic mistake of passing values in the wrong order. Values go in as `Pair`s or as a `Map`:

```kotlin
session.createNamedQuery("SELECT id, cognomen FROM senators WHERE province_id = @province AND active = @active")
    .fetchRows("province" to 7, "active" to true)

session.createNamedQuery("... WHERE province_id = @province")
    .fetchRows(mapOf("province" to 7))
```

Names are rewritten to `$n` before the query is sent, and a name repeated in the statement collapses to one placeholder: `WHERE created > @date OR updated > @date` sends a single parameter and you supply `date` once. Positional queries can do the same by repeating `$1` yourself — this is where both differ from JDBC's `?`, which needs the value passed again for every occurrence.

The rewriter understands SQL well enough to leave the rest of your statement alone — an `@` inside a string literal, a quoted identifier, a line or block comment, or a `$$ … $$` block is not a parameter. PostgreSQL's `@`-operators are safe too, since a parameter name cannot start with an operator character:

```kotlin
// Both @-operators survive; only @filter and @q are parameters
session.createNamedQuery("SELECT * FROM t WHERE data @> @filter AND tsv @@ to_tsquery(@q)")
```

> [!NOTE]
> Parameters are sent in binary under a concrete type, which is what makes a bare `null` ambiguous and can make PostgreSQL pick the wrong overload of a function. [Functions and Procedures](functions-procedures.md#argument-types-decide-which-routine-runs) covers that in detail, and `withPgType` is the way out.

### Quoting a name that comes from outside

A placeholder is a value, so a table or column chosen at runtime has to be interpolated into the SQL text instead. Where the name cannot be mapped onto one you wrote yourself, `quoteAsPgIdentifier()` is the escape hatch:

```kotlin
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier

val table = tenantTable.quoteAsPgIdentifier()   // legio X  ->  "legio X"
session.createNativeQuery("SELECT count(*) FROM $table").fetchFieldStrict<Long>()
```

It wraps the name in double quotes and doubles every quote inside it; a NUL character, which PostgreSQL cannot hold in an identifier at all, throws `StatementException(SYNTAX_ERROR)`. The driver uses it on its own behalf for `LISTEN` and `UNLISTEN` channels and for savepoint names, so those already take arbitrary strings safely. It always quotes, which makes the name case-sensitive — `CREATE TABLE MixedCase` stores `mixedcase`, and quoting the string `MixedCase` matches nothing.

## Choosing a fetch method

Reach for a `fetch*` method whenever the statement produces rows — a `SELECT`, or an `INSERT` / `UPDATE` / `DELETE` with `RETURNING`. Three families, differing only in what they hand back:

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

Object mapping goes through the internal `ResultMapper`, which is also where [custom converters](#per-query-converters) hook in; how columns find their way onto constructor parameters is described in [Type System](type-system.md).

## Reading a `Row`

`Row` is decoded up front, not a cursor. Every column is decoded when the row is built, so nothing is read off the connection afterwards and a row can be kept, passed to another thread, or read in any order.

What is *not* finished at that point is the conversion: `row.get<T>()` resolves a converter when you call it, through the registries the row's query is attached to. In practice that only matters if those registries change while you are still holding rows — see [Concurrency](concurrency.md#what-can-cross-a-thread-boundary).

Values come out through `get`, by name or by zero-based index:

```kotlin
val cognomen: String = row.get("cognomen")
val id: Int          = row.get(0)
val profile: Senator = row.get("profile")   // any type a converter can produce
```

Each has a non-reified twin taking a `KType` — `get<T>(index, targetType)` — which is what the reified one delegates to, and the only form reachable from Java.

The rest of the surface is metadata, useful when the shape of the result is not known in advance:

| Member                 | Gives                                                                              |
|:-----------------------|:-----------------------------------------------------------------------------------|
| `columnNames`          | Every column name, in order.                                                       |
| `getColumnIndex(name)` | The index for a name, or `MappingException(COLUMN_NOT_FOUND)`.                     |
| `hasColumn(name)`      | Whether a column carries that name — what `getColumnIndex` answers by raising.      |
| `getRaw(index)`        | The decoded value *before* conversion — `Int`, `String`, `PgComposite`, `PgArray`. |
| `getOid(index)`        | The PostgreSQL OID of that column's type.                                          |
| `metadata`             | `RowMetadata` — what the result says about its own columns.                        |

`hasColumn` is there for a result whose shape is not fixed — a projection assembled at runtime, or a `RETURNING` that differs by branch. Asking is what such code means; a `try`/`catch` around a lookup is not, and it catches the mapping failures you did want to hear about along with the one you were testing for.

Because decoding is eager and conversion is lazy, asking one row for two different shapes costs one decode and two conversions:

```kotlin
val asMap: Map<String, Any?> = row.get(0)
val asClass: Senator = row.get(0)     // same bytes, not decoded again
```

> [!NOTE]
> **A duplicated column name resolves to the first one.** `SELECT s.id, p.id FROM senators s JOIN provinces p …` produces two columns called `id`, and `row.get<Int>("id")` returns the first without complaining — the name-to-index map keeps the earliest entry. That is JDBC's behaviour too, where `ResultSet.findColumn` has always answered with the first match, so it is one habit that carries over unchanged. Alias the columns in the SQL (`p.id AS province_id`) when you need both, or read them by index.

### What a column says about itself

`row.metadata` is resolved once for a result, out of the `RowDescription` that opens it, and shared by every row
that follows. `getColumn(index)` and `getColumn(name)` hand back a `ColumnMetadata`:

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

The naming costs no extra query: every table, view and materialized view already has a row type under the
relation's own name, and the driver loads them all with the rest of the catalog. Three states are worth telling
apart:

* **`origin == null`** - the column is not a reference to a stored column at all: an expression, a literal, the
  output of a function.
* **`origin` present, its names `null`** - the server tracked the value back to a relation the type catalog does
  not describe. That means `pg_catalog` and `information_schema`, which the type load skips, and anything created
  since the last load, which `reloadTypes()` fills in.
* **`origin` present and named** - the ordinary case.

`typeModifier` is left as the server sends it, because reading one takes knowledge of the particular type: a
`numeric(10,2)` arrives as `((10 shl 16) or 2) + 4`, a `varchar(64)` as `68`, a `timestamp(3)` as `3`. It is the
one thing here with no other source in a result, which is why it is carried raw rather than dropped.

A column whose type the catalog does not describe fails when the result is described, rather than when a value is
read from it. Such a column could not have been decoded either way - a type the load never saw has no codec
either - and failing on the description makes it fail the same way every time.

> [!NOTE]
> **A domain column is described by the type underneath it.** PostgreSQL resolves a domain to its base type before
> describing a column - for a plain reference, an explicit cast and a function's result alike - so `type` is never a
> `PgType.Domain`. A column declared over a domain of `numeric` arrives as `numeric`, and only `origin` still says
> which column it was. A domain does survive one level down, as an array's element type or a composite's attribute.

## Nullability and the Strict variants

Two independent things can go missing here, and they are handled by different halves of the API. **How many rows came back** is what the `Strict` suffix governs; **whether a value was there at all** is governed by how you type `T`. Confusing the two is the usual source of surprise, so here is the whole matrix for the field family:

| Situation                                  | `fetchField<T>()`                                  | `fetchFieldStrict<T>()`                            |
|:-------------------------------------------|:---------------------------------------------------|:---------------------------------------------------|
| No rows, `T` nullable                      | `null`                                             | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| No rows, `T` not nullable                  | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| More than one row                          | `InvalidOperationException(INCORRECT_RESULT_SIZE)` | `InvalidOperationException(INCORRECT_RESULT_SIZE)` |
| One row, value is `NULL`, `T` nullable     | `null`                                             | `null`                                             |
| One row, value is `NULL`, `T` not nullable | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     | `MappingException(REQUIRED_ATTRIBUTE_MISSING)`     |

The thing to read twice is that **`T`'s nullability covers both ways a value can be absent**, and treats them the same. A row that never matched and a row carrying SQL `NULL` are the same answer as far as your type is concerned — you asked for a `String`, there is no `String` — so both raise `REQUIRED_ATTRIBUTE_MISSING`. If the lookup is allowed to find nothing, say so: `fetchField<String?>()`, and the same goes for the list form, `fetchFields<String?>()`.

That is what separates it from the `Strict` suffix, which counts rows and nothing else. `fetchFieldStrict<String?>()` still refuses an empty result — with `INCORRECT_RESULT_SIZE`, because zero rows is the wrong *number* of rows regardless of what a value would have been.

**Only the field family works this way, and the signature is what tells you so.** `fetchObject<T : Any>` bounds `T` to a non-null type and `fetchRow` returns a plain `Row?`, so neither has a nullable `T` to read an intention from — they cannot distinguish "I expect a row" from "there may be none", and simply return `null` when nothing matched; `fetchRowStrict` and `fetchObjectStrict` are how you demand a row there. `fetchField<T>` is the one that leaves `T` unbounded, and that is deliberate: it is the parameter you use to say whether a value is required.

Which is why it returns `T` rather than `T?`. Nullability lives in `T` and comes back exactly as you declared it, so a non-nullable one needs no unwrapping at the call site:

```kotlin
val cognomen: String  = session.createNativeQuery("SELECT cognomen FROM senators WHERE id = $1").fetchField(7)
val patron: String?   = session.createNativeQuery("SELECT patron FROM senators WHERE id = $1").fetchField<String?>(7)
```

`Row.get<T>()`, `fetchFields<T>(): List<T>` and `fetchFieldStrict<T>(): T` read `T` the same way, so the whole family is consistent: you never receive a `null` you did not ask for, and never have to `!!` one away.

All of them ask the server for at most two rows, so a single-result query that accidentally matches a million does not drag them across the wire before failing.

## Streaming large results

`fetchRows()` and friends materialize everything into memory. For a result set that will not comfortably fit — an audit of every citizen in the census — use the `forEach*` methods, which pull rows in batches and hand them to your block one at a time. `fetchSize` is the batch size and has no default, so you always state it:

```kotlin
session.createNativeQuery("SELECT * FROM citizens WHERE province_id = $1")
    .forEachObject<Citizen>(7, fetchSize = 500) { citizen ->
        writeToExportFile(citizen)
    }
```

Memory stays flat, and what keeps it flat is that no row is kept: each one reaches your block and is done with, however
many the query returns. `fetchSize` governs the other side of the exchange — how many rows the server sends before it
pauses and waits to be asked for the next batch.

**`fetchSize = 0` asks for the whole result in a single `Execute`.** There are no batches to pause between, so it costs
one round trip rather than one per batch. That is the setting for a loop whose point is to *fold* a result — deduplicate
it, merge adjacent rows, run a total — rather than to survive its size: `fetchRows()` would build the entire `List`
first, which is the one thing the fold was avoiding, and batching buys nothing when every row is going to be read
anyway. It suits a small result as much as a large one; on a small one the per-batch round trips are the only thing
batching adds.

Reach for it knowing that a fold which deduplicates or groups is one PostgreSQL does better than your block will.
`DISTINCT` and `GROUP BY` hand back a result already reduced, and the rows you would have thrown away never cross the
wire. Keep `fetchSize = 0` for the fold that SQL cannot express.

A negative `fetchSize` is refused outright, naming the value.

Three things about that loop are worth knowing before you rely on it.

**The whole iteration is one statement, for as long as it takes.** The driver keeps the result open across batches without closing the exchange, so the server treats it as a single running statement from the first batch to the last. Whatever your block does — writing files, calling services — happens inside that statement's lifetime.

> [!WARNING]
> **`statement_timeout` covers the entire loop, including time spent in your block.** With `statement_timeout = 1s` and a block taking 200 ms per row, an iteration was cancelled after 10 rows and surfaced as `ExecutionAbortedException(QUERY_CANCELED)`. The session recovers and stays usable, but the rest of the rows are gone. If a slow block is unavoidable, either raise the timeout for that session or collect the rows first and do the slow work afterwards.

**The session is busy for the duration.** Your block runs while the driver is still reading the result, so it must not touch that same session — see [below](#do-not-re-enter-the-session-while-rows-are-being-read).

**Exceptions from your block do not escape unchanged.** Anything that is not an `OctaviusException` is wrapped in `MappingException(BLOCK_FAILED)` with your exception as its `cause`, because the driver has to finish draining the result before it can rethrow — and by then the frame that knows the query is gone, so the wrapper is what carries the query context. See [Error Handling](exceptions.md#errors-raised-during-row-mapping) if you need your own exception type to survive.

## Do not re-enter the session while rows are being read

Most `fetch*` methods convert each value as it arrives, before the exchange with the server is finished. Any code of yours that runs at that moment — a `forEach*` block, or a custom `ResultConverter` — is therefore executing *inside* an unfinished exchange, and issuing a query on that same session from there collides with it.

This has nothing to do with streaming. A plain `fetchFields<T>()` whose converter queries the session fails exactly like the streaming version does; what matters is only whether your code runs during the read:

| Where your code runs                                 | Querying the same session from there |
|:-----------------------------------------------------|:-------------------------------------|
| `forEach*` block                                     | Collides                             |
| `ResultConverter` under any mapping fetch            | Collides                             |
| After `fetchRows()`, calling `row.get<T>()` yourself | Fine — the exchange is already over  |

The driver refuses such a call outright, **before anything reaches the wire**: you get `InvalidOperationException(CONNECTION_BUSY)`, or that same exception as the `cause` of a `MappingException(CONVERSION_ERROR)` when it came from a converter — the wrapper is what carries the `path` to the column being mapped, so read both. Nothing is corrupted, the connection stays healthy, and the next statement works normally — only the operation you interrupted is lost. The same guard covers a `COPY` started from that position.

If code in that position needs the database, give it a second session.

## Statements that return nothing

- **`update()`** — DML that changes rows (`INSERT`, `UPDATE`, `DELETE`). Runs through the Extended Query Protocol with full parameter binding and returns the affected row count as a `Long`.
- **`execute()`** — raw execution with no result and no count: DDL, `SET`, administrative commands. It uses the Simple Query Protocol, so it **cannot bind parameters**, and there is no reading rows from it.

```kotlin
val promoted = session.createNativeQuery("UPDATE senators SET rank = $1 WHERE province_id = $2")
    .update("consul", 7)

session.createNativeQuery("CREATE INDEX idx_senators_province ON senators (province_id)").execute()
```

Handing `execute()` a row-returning statement is an error, not a silent discard: it throws `InvalidOperationException(UNEXPECTED_RESULT)`. That default is the useful one — a `SELECT` sent here instead of to a `fetch*` method would otherwise do nothing, quietly. Where the SQL is a script written elsewhere and a `SELECT` in it is legitimate, `execute(ignoreRows = true)` drops the rows rather than raising. They are dropped either way; the flag only decides whether their arrival is reported.

> [!IMPORTANT]
> An `INSERT` or `UPDATE` with a `RETURNING` clause returns rows, so it belongs to the `fetch*` family, not `update()`. Getting a generated id back is `fetchFieldStrict<Long>()`:
>
> ```kotlin
> val newId: Long = session.createNativeQuery("INSERT INTO senators (cognomen) VALUES ($1) RETURNING id")
>     .fetchFieldStrict("Cato")
> ```

### `execute()` takes a whole script

Because it speaks the Simple Query Protocol, `execute()` accepts several statements separated by `;`, and sends them in a single round trip:

```kotlin
session.createNativeQuery("""
    CREATE TABLE castra (id serial PRIMARY KEY, nomen text NOT NULL);
    CREATE INDEX idx_castra_nomen ON castra (nomen);
    INSERT INTO castra (nomen) VALUES ('Vindobona')
""").execute()
```

PostgreSQL wraps a script like that in an implicit transaction, so it is all or nothing: a statement failing halfway takes the ones before it down with it and nothing is left half-applied. Called inside `transaction.required { }` it simply joins the transaction already open, where the usual rollback rules apply instead. The ban on rows covers every statement in the script rather than only the first — one stray `SELECT` anywhere in it and the whole call is an `InvalidOperationException(UNEXPECTED_RESULT)`, unless it was called as `execute(ignoreRows = true)`. A script that came out of `pg_dump` is the usual reason to: it emits `SELECT pg_catalog.setval(...)` for every sequence it carries.

The `fetch*` family and `update()` cannot do the same. They send one statement to `Parse`, where PostgreSQL permits exactly one, so `SELECT 1; SELECT 2` comes back as `StatementException(SYNTAX_ERROR)`. Nor can a script bind anything: a `$1` inside one is `StatementException(UNDEFINED_OBJECT)`, there being no `Bind` step to give it a value. Whatever varies belongs in a statement of its own, run through `update()`.

## Cancelling a query in flight

`session.cancelQuery()` asks the server to abandon whatever that session is currently running. It has to be called **from a different thread**: the one that issued the query is blocked inside it and will not reach the call. The request does not queue behind the running statement either — it travels over a separate short-lived connection, which is how PostgreSQL cancellation works at the protocol level.

That second connection is negotiated under the session's own [`sslmode`](initialization.md#ssl), so the cancel key it carries is encrypted wherever the session itself is. Under `REQUIRE` or stronger, a server that will not encrypt means the request is *not sent* rather than sent in the clear. Its connect and its reads are bounded by [`cancelSignalTimeout`](initialization.md#network-and-limits) — 10 seconds by default, and a budget of its own because a cancel can get stuck on a server the session is not stuck on.

`cancelQuery()` is not instantaneous, either: after sending the request it waits for the backend to close the cancel connection, which is how PostgreSQL acknowledges one. That is normally immediate, and it is what distinguishes "the server read it" from "we wrote it into a socket and hung up".

```kotlin
val work = OctaviusDispatchers.VirtualExecutor.submit {
    session.createNativeQuery("SELECT count(*) FROM census_of_every_citizen").fetchFieldStrict<Long>()
}

// Elsewhere, once it has gone on long enough
session.cancelQuery()
```

The blocked call then fails with `ExecutionAbortedException(QUERY_CANCELED)` (SQLSTATE `57014`), the session stays usable, and the next statement runs normally.

Two things it does not promise. **It never throws** — if the cancel request cannot be delivered, the failure is swallowed and the query simply keeps running, so a return means "asked", not "stopped".

And **it can hit the wrong statement.** A cancel request carries a backend process id and a key; there is no statement identifier in it. The server signals that connection to abandon whatever it is running when the signal is handled — so if the statement you meant finished first and another has since started on that connection, the cancel takes that one instead. On a session only one thread ever touches, the statement is still running while you cancel it and the question does not arise; where several threads share a session, or where a cancel is fired at a session about to go back to the pool, it can. [Concurrency](concurrency.md#why-a-cancel-can-hit-the-wrong-statement) walks through the sequence.

For a limit that should apply to every statement rather than one, set `statement_timeout` as a [startup parameter](initialization.md#startup-parameters) — no second thread, no second connection, and it covers the whole session. See [Concurrency](concurrency.md#cancelling-a-query-in-flight) for how this interacts with the connection lock.

## Per-query converters

Conversion normally goes through the session's type manager. When one query needs different treatment — a column parsed into a domain type, a DTO serialized a particular way — register a converter on that query instance alone:

- `registerResultConverter(converter)` — database value to Kotlin object.
- `registerParameterConverter(converter)` — Kotlin object to query parameter.

Both return the query, so they chain onto the call:

```kotlin
session.createNativeQuery("SELECT * FROM tabulae WHERE id = $1")
    .registerResultConverter(RomanNumeralConverter())
    .fetchObjectStrict<Tabula>(1)
```

Each query gets its own registry layered over the session's, so a converter registered here is visible to this query and invisible everywhere else. [Type System](type-system.md) covers the layering and how to register converters globally instead.
