# Octavius vs Legacy JDBC

*The Republic was never abolished. Consuls were still elected, the Senate still met, the old titles were still spoken
aloud in the Forum — and behind those familiar forms the machinery of government had been replaced entirely. Octavius
keeps the JDBC interfaces on the outside for much the same reason, and asks to be judged by what runs underneath them.*

Octavius takes a deliberately radical stance on database access in the JVM world. 
It technically implements `java.sql.Driver` and `java.sql.Connection` — but it strips out and disables most of what the JDBC specification actually asks of a driver. `jdbcCompliant()` returns `false`, and means it.

The reasoning: JDBC's core contracts (`ResultSet`, `Statement`) were designed for a different era of Java. They push developers toward stateful, mutable objects, manual index-based binding, and a constant risk of resource leaks if something isn't closed. 
Octavius replaces all of that with a functional, Kotlin-first API — and even the driver's own name nods to reinvention: Octavius was the birth name of Gaius Octavius, before Rome came to know him as Augustus.

## The "Trojan Horse" Strategy (Connection Pools)

Fittingly for a driver named after Augustus — who traced his lineage back to Aeneas, the Trojan refugee of legend — Octavius's JDBC compatibility layer borrows a trick from the same story. If the driver throws away most of JDBC, why implement `java.sql.Connection` at all?

The answer is connection pooling. Production JVM applications lean on mature pools like **HikariCP**, and those pools need standard JDBC surfaces to manage, validate, and recycle connections. So Octavius implements just enough JDBC — a gift left outside the gates — to get waved through:
- Connection lifecycle (`close()`, `isClosed()`, `isValid()`)
- Transaction control (`commit()`, `rollback()`, `setAutoCommit()`, isolation level, read-only mode, savepoints)
- Network timeout handling
- A plain `java.sql.Statement`, narrowed to `execute()` and `executeUpdate()` — no `ResultSet`, no batching. It exists so a pool can run its `connectionInitSql`, and it is what carries Spring Boot's `spring.sql.init` scripts.

That `Statement` comes with a hard edge worth knowing before you configure a pool: **neither `execute()` nor `executeUpdate()` accepts a statement that returns rows.** A `RowDescription` arriving on either path is an `InvalidOperationException(UNEXPECTED_RESULT)`, because with no `ResultSet` to hand back there is nowhere for those rows to go. So the reflex `connectionTestQuery = "SELECT 1"` does not merely fail to help — it throws on every probe, and a pool reading that as a dead connection will discard healthy ones in a loop.

Leave the test query unset. HikariCP then validates through `isValid()`, which Octavius implements properly: it runs an empty query, returns no rows, and only ever tightens the connection's existing network timeout for the duration of the check. A pool that insists on a query string needs one that returns nothing — `SET`-style, not `SELECT`-style.

Once the connection has cleared the gate, though, you stop reaching for JDBC. Instead, pull the native `OctaviusSession` straight out of the `DataSource`:
```kotlin
// Internally calls .getConnection() and wraps the result in the native API
val session = dataSource.getOctaviusSession()

// From here on, 'session' is all you need
```

## What Is Explicitly Unsupported?

Reaching for any of the following on the underlying JDBC connection — or on the `Statement` it hands out — throws an `InvalidOperationException` with reason `FEATURE_NOT_SUPPORTED`.

That exception is **not** a `SQLException`. The methods Octavius does implement wrap their failures in [`SQLExceptionWrapper`](exceptions.md#sqlexceptionwrapper), because pools need to read SQLSTATE off something — but the unsupported ones throw the raw Octavius exception, an unchecked `RuntimeException`. A `catch (SQLException e)` inherited from legacy JDBC code will not catch it, and the failure sails past the handler that was written for exactly this case.

A further handful of methods neither work nor throw. They [answer quietly](#what-answers-quietly-instead-of-throwing), which is the half worth reading twice.

### 1. `PreparedStatement` and `CallableStatement`
No index-based binding (`stmt.setString(1, "value")`). Octavius replaces both with `createNativeQuery` and `createNamedQuery`, removing the statement lifecycle entirely and making parameter binding harder to get wrong.

JDBC escape syntax goes with them: `{call proc(?)}`, `{fn now()}`, `{d '2026-08-15'}` are never rewritten, and `setEscapeProcessing()` throws. Calling a routine is [an ordinary `SELECT` or `CALL`](functions-procedures.md).

### 2. `ResultSet`
There's no mutable cursor to walk with `.next()`. Octavius hydrates results straight into memory — as `List<Row>`, a single typed field via `fetchField<T>()`, or fully-formed Kotlin data classes via `fetchObject<T>()`. Results too large to hold are [streamed with `forEach*`](queries.md#streaming-large-results) instead of tuned with `setFetchSize()`.

The accessories go with the cursor: `setMaxRows()`, `setFetchSize()`, `setQueryTimeout()`, and generated-key retrieval (`RETURN_GENERATED_KEYS`, `getGeneratedKeys()`) all throw. The [migration map](#migration-map) below says what replaces each.

### 3. JDBC Batching
`addBatch()` / `executeBatch()` aren't implemented in their standard JDBC shape — both throw. Octavius favors PostgreSQL-native bulk techniques instead: [`UNNEST`-based inserts](bulk-writes.md), which [outperform classic batching by roughly 3× in the benchmarks](performance.md), and the [`COPY` protocol](copy.md) for genuinely large loads. The array form is the broader of the two — it covers `UPDATE` and `DELETE`, takes `RETURNING` and `ON CONFLICT`, and needs no change to the shape of your data.

### 4. Legacy LOBs (BLOB, CLOB)
`createBlob()` / `createClob()` don't exist here. Binary and text data map directly to plain Kotlin `ByteArray` and `String`, backed by PostgreSQL's `bytea` and `text` through the type catalog. For payloads past what a single `bytea` should carry, PostgreSQL's own large objects are exposed as [a first-class API](large-objects.md) on the session — no unwrapping to a vendor interface.

### 5. DatabaseMetaData
The heavyweight JDBC metadata API is skipped entirely. If you need metadata, query `pg_catalog` directly through `OctaviusSession`.

This is the omission with the longest shadow: Hibernate, Spring Data JDBC and JPA, Flyway and Liquibase all identify the database through `DatabaseMetaData` before they do anything else, so none of them run on Octavius. Where one of them is non-negotiable — schema migrations at startup, usually — the practical answer is a second `DataSource` on `pgjdbc` dedicated to that tool, with Octavius left to the application.

### 6. The vendor extension points
`createArrayOf()`, `createStruct()`, `createSQLXML()`, `getTypeMap()` / `setTypeMap()` — the seams JDBC left for a driver to bolt its own types onto. Octavius doesn't need them: pass a Kotlin `List` or a data class and [the registry](type-system.md) encodes it, having read the OIDs out of *your* catalog at connect time. `xml` decodes as a `String`, so there is no `SQLXML` to construct either.

### 7. Schema and catalog

`setSchema()` / `getSchema()` and `setCatalog()` / `getCatalog()` throw. Neither is connection state in PostgreSQL: a
catalog is the database itself, which nothing on an open connection can change, and what JDBC calls the connection's
schema is the head of `search_path` — a server-reported setting rather than a property of the connection.
`session.searchPath` reads that one; `SELECT current_database()` answers the other.

**A pool configured with a default schema or catalog fails when it opens the connection** — HikariCP's `schema` and
`catalog` properties, `spring.datasource.hikari.schema` among them. Supply `search_path` as
a [startup parameter](initialization.md#startup-parameters) instead, where it becomes part of every connection's
identity rather than something set afterwards
and [left behind for the next borrower](initialization.md#what-survives-a-return-to-the-pool).

## What Answers Quietly Instead of Throwing

The JDBC spec handles database notices by silently accumulating `SQLWarning` objects in a linked list on the `Connection` or `Statement`. Forget to call `clearWarnings()` after every execution and that list becomes a slow, application-killing leak over millions of queries.

Octavius drops the pull-based trap entirely and pushes instead: notices reach a `NoticeHandler` the moment they arrive, and nothing accumulates. Configure one through the [`noticeHandler` property](initialization.md#network-and-limits).

`getWarnings()` **returns `null`** and `clearWarnings()` does nothing, rather than throwing — connection pools call both routinely on every borrow, so failing there would break pooling for no gain. The same reasoning covers a short list of other methods, each answering with a plausible constant:

| Call                                            | What comes back          | Where the real answer lives                                              |
|:------------------------------------------------|:-------------------------|:-------------------------------------------------------------------------|
| `getWarnings()` / `clearWarnings()`             | `null` / nothing happens | A `NoticeHandler`                                                        |
| `getClientInfo()`                               | An empty `Properties`    | — (the name-taking overload and both setters throw)                      |
| `Statement.getUpdateCount()`                    | `-1`                     | The return value of `executeUpdate()`                                    |
| `Statement.getResultSet()` / `getMoreResults()` | `null` / `false`         | — (`execute()` never produces rows)                                      |
| `Driver.getPropertyInfo()`                      | An empty array           | [The configuration reference](initialization.md#configuration-reference) |

One of these is worth spelling out for anyone migrating: **your existing `getWarnings()` calls will not fail** — they will report nothing, forever. Move that logic to a `NoticeHandler`.

Running the other way, one method throws where the spec says it shouldn't: `abort(executor)` closes the connection and then throws a `SQLExceptionWrapper`, deliberately, so the pool marks the connection dead and evicts it rather than handing it to the next borrower. `session.abort()` does the same work without the throw.

## Migration Map

Coming from `pgjdbc`, this is where each habit lands:

| Plain JDBC / `pgjdbc`                           | Octavius                                                                                                                      |
|:------------------------------------------------|:------------------------------------------------------------------------------------------------------------------------------|
| `prepareStatement(sql)` + `setString(1, …)`     | `createNativeQuery` with `$1`, or `createNamedQuery` with `@name`                                                             |
| `prepareThreshold`, statement caching           | Nothing — [every execution is parsed afresh](#nothing-is-prepared-server-side)                                                |
| `executeQuery()` + `while (rs.next())`          | `fetchRows()`, `fetchObjects<T>()`, `fetchField<T>()`                                                                         |
| `setFetchSize(n)` + a cursor loop               | [`forEach*`](queries.md#streaming-large-results)                                                                              |
| `setMaxRows(n)`                                 | `LIMIT`                                                                                                                       |
| `RETURN_GENERATED_KEYS` + `getGeneratedKeys()`  | `RETURNING id`, read with `fetchFieldStrict<Long>()`                                                                          |
| `setQueryTimeout(s)`                            | `statement_timeout` as a [startup parameter](initialization.md#startup-parameters); `session.cancelQuery()` for one in flight |
| `addBatch()` / `executeBatch()`                 | [`UNNEST` inserts](bulk-writes.md), or [`COPY`](copy.md)                                                                      |
| `prepareCall("{call proc(?)}")`                 | [`CALL proc($1)`](functions-procedures.md) as an ordinary statement                                                           |
| `conn.unwrap(PGConnection).getLargeObjectAPI()` | `session.largeObjects` — [Large Objects](large-objects.md)                                                                    |
| `conn.unwrap(PGConnection).getCopyAPI()`        | `session.copy` — [COPY](copy.md)                                                                                              |
| `createArrayOf(…)` / `createStruct(…)`          | Pass the Kotlin `List` or data class; [the registry](type-system.md) encodes it                                               |
| `conn.getMetaData()`                            | Query `pg_catalog` through the session                                                                                        |
| `conn.setSchema(…)` / `conn.getSchema()`        | `search_path` as a [startup parameter](initialization.md#startup-parameters); `session.searchPath` reads it back              |
| `conn.setCatalog(…)` / `conn.getCatalog()`      | `SELECT current_database()` — the database a connection is on cannot change                                                   |

Note which way those last rows point. `pgjdbc` hands you a vendor interface by *unwrapping* the connection; Octavius hands you a session by *wrapping* it. `connection.getOctaviusSession()` on a pooled `Connection` — or `dataSource.getOctaviusSession()` on the pool itself — builds the session around the connection you already hold, which is why `close()` still returns that connection to the pool. Getting down to the protocol underneath is the session's own business, done once, internally.

The one unwrap left on the public surface points at the `DataSource`, not the connection: `dataSource.unwrapToOctavius()` digs out the `OctaviusDataSource` behind a pool configured with it.

## Nothing Is Prepared Server-Side

One row in that map has no counterpart at all, and it is worth a paragraph of its own. `pgjdbc` promotes a statement to a *named* server-side prepared statement once it has been executed `prepareThreshold` times — five, by default — and keeps a per-connection cache of them. Octavius has no such machinery and no property to tune: every execution goes out as `Parse` into the **unnamed** statement, then `Bind` and `Execute`. Run the same query twenty times and `pg_prepared_statements` still has nothing in it.

What you give up is plan reuse. Parsing and planning happen on every execution, which is real work for a trivial statement in a tight loop — and where that loop is the problem, fewer round trips through [`UNNEST`](bulk-writes.md) or [`COPY`](copy.md) buy more than a cached plan would.

How much work is [measured rather than argued](performance.md#what-not-preparing-costs): **16.3 µs a statement**, taken with pgjdbc against itself, its own server-side prepare switched on and then off, on a primary-key lookup chosen so the figure would come out as large as it can. Octavius lands on pgjdbc's unprepared figure, so that gap is the feature and nothing else about the driver.

What a cache brings with it is the other half of the trade:

* **The plan stops following the parameter.** PostgreSQL plans the unnamed statement at `Bind`, with the values in hand, so every execution is planned for its own arguments. A statement prepared on the server gets that for its first five executions; from the sixth, PostgreSQL compares the generic plan's *estimated* cost against the average of those five and keeps whichever looks cheaper, for every value after that. On a column where 99% of rows shared one value, [that estimate came out 254× low](performance.md#what-not-preparing-costs) and the switch happened. What it costs from there depends on the table and on which value you are unlucky with; nothing measures the plan it settled on and reconsiders.
* **A named statement can go missing.** It belongs to one backend, and anything that clears that backend's session state takes it along: PgBouncer in transaction-pooling mode, a `DISCARD ALL`, a failover. The client is then the one that has to recognise `26000`, prepare it again and re-run the statement — which means being right about when re-running is safe.
* **No stale cached plans.** `cached plan must not change result type`, the error that follows a migration on a long-lived pooled connection, has no cache to come from.
* **Nothing to deallocate.** A connection returned to the pool carries no statements forward, and there is no leak to chase when a pool is large and the query set is larger.

None of which makes the absence something to prefer. It is one side of a trade: 16 µs a statement, against a plan fitted to its arguments and a good deal of machinery kept out of the driver. Where plan reuse is worth more than that on your workload, pgjdbc's model is the one that has it.

## Summary

Dropping JDBC's historical baggage buys Octavius:
- **No resource-leak busywork** — no nested `try-with-resources` blocks just to close a `ResultSet` and a `Statement`.
- **A genuinely Kotlin-idiomatic API** — reified generics and safe mapping in place of old Java patterns.
- **Protocol-level safety** — queries and DML go through the Extended Query Protocol's Parse/Bind/Execute cycle, spoken directly rather than emulated on top of another driver. (Statements with nothing to bind — `execute()`, transaction control, `LISTEN`, `COPY` — use the Simple Query Protocol, which is what those are for.)
