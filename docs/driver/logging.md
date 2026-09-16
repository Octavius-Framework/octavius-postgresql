# Logging

*The vigiles walked their rounds every night of the year and filed nothing when the streets were quiet; a report existed
because something had happened, not because anyone had been watching. Raising a level here works the same way — it does
not make the driver more careful, it only asks it to write down rounds it was already walking. What changes between
`info` and `trace` is how much of that walking you have agreed to read.*

Octavius logs through [SLF4J](https://www.slf4j.org/), so it writes wherever the rest of your application already
writes. Nothing is configured on the driver itself: there is no `loggerLevel` property and no `logUnclosedConnections`
switch, because a level in your logging config already says everything such a property would.

## Table of Contents

* [You supply the backend](#you-supply-the-backend)
* [Logger names](#logger-names)
* [What each level says](#what-each-level-says)
* [Server notices](#server-notices)
* [The driver does not log what it throws](#the-driver-does-not-log-what-it-throws)
* [What never reaches the log](#what-never-reaches-the-log)
* [Following one connection](#following-one-connection)
* [What tracing costs](#what-tracing-costs)
* [Configuration recipes](#configuration-recipes)

## You supply the backend

The driver depends on `slf4j-api` and nothing else. SLF4J with no backend on the classpath is a no-op that prints one
warning at startup and then discards everything — so a driver that appears silent at every level is usually a missing
dependency, not a missing log statement:

```kotlin
runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
```

Spring Boot's `spring-boot-starter` already brings Logback, so an application built on
[the Spring integration](spring-integration.md) has one.

## Logger names

Every name is the fully-qualified class it comes from, with one deliberate exception — notices carry their own name so
they can be turned up or down without touching anything else.

| Logger                                                                     | What it carries                                                                                       |
|:---------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------|
| `io.github.octaviusframework.driver.Notice`                                | Server notices, at a level mirroring the server's own severity                                        |
| `io.github.octaviusframework.driver.jdbc.OctaviusConnectionFactory`        | One line per physical connection opened                                                               |
| `io.github.octaviusframework.driver.jdbc.OctaviusConnection`               | Transactions, savepoints, isolation, cancellation, abort, validation probes                           |
| `io.github.octaviusframework.driver.session.OctaviusSessionImpl`           | Session close and abort, and a close that evicted its connection instead of returning it              |
| `io.github.octaviusframework.driver.transaction.TransactionManager`        | Auto-commit left unrestored after a transaction scope committed                                       |
| `io.github.octaviusframework.driver.execution.QueryExecutor`               | Every statement and its duration                                                                      |
| `io.github.octaviusframework.driver.registry.GlobalCatalogStore`           | The type catalog load, and every explicit reload of it                                                |
| `io.github.octaviusframework.driver.copy.CopyManager`                      | COPY transfers, with row and byte counts                                                              |
| `io.github.octaviusframework.driver.notification.NotificationManager`      | `LISTEN` / `UNLISTEN` and the listener loops                                                          |
| `io.github.octaviusframework.driver.ssl.SslNegotiator`                     | Whether the connection ended up encrypted, and under which cipher                                     |
| `io.github.octaviusframework.driver.auth.Authenticator`                    | Handshake steps and session parameters                                                                |
| `io.github.octaviusframework.driver.auth.ScramSha256Authenticator`         | The SCRAM mechanism actually negotiated                                                               |
| `io.github.octaviusframework.driver.io.PgStream`                           | Session parameters that change, socket close, protocol messages ignored, a `NoticeHandler` that threw |
| `io.github.octaviusframework.driver.lo.LargeObject`                        | A descriptor that could not be closed                                                                 |
| `io.github.octaviusframework.driver.spring.OctaviusJdbcTransactionManager` | A rollback on a connection that had already gone                                                      |

All of them sit under `io.github.octaviusframework.driver`, so one entry configures the lot — the last one
included, which ships in the separate `driver-spring-integration` artifact and is only there if you took it.

Two things that build on the driver keep names of their own, and neither is reached by an entry here:
[`migrations`](../migrations/logging.md) has its own page, and the client writes nothing at all — see
[Logging](../client/README.md#logging) there.

## What each level says

The split is by **how often a line appears**, not by how important it sounds. What follows is the driver reporting on
itself.

| Level   | Frequency                                      | What you get                                                               |
|:--------|:-----------------------------------------------|:---------------------------------------------------------------------------|
| `error` | Practically never                              | Only a `NoticeHandler` of yours that threw                                 |
| `warn`  | Practically never                              | A connection on its way out, with the reason recorded only here            |
| `info`  | Once per database, plus every `reloadTypes()`  | The type catalog load, with its type count and duration                    |
| `debug` | Once per connection, per transaction, per COPY | Lifecycle: connections, transactions, savepoints, `LISTEN`, TLS, transfers |
| `trace` | Twice per statement                            | Every statement, its duration, and session parameters that move            |

**Server notices cut across the whole table.** They are the server talking rather than the driver, so they take their
level from its severity instead of from this scheme, and `warn`, `info` and `debug` each carry them. A codebase that
leans on `RAISE NOTICE` will see plenty at `info` whatever the row above says, and one that raises warnings will find
`warn` is not "practically never" at all. They are on a logger name of their own precisely so that this is a separate
dial — see [Server notices](#server-notices).

### `info` is the catalog

The only thing the driver considers worth an unprompted line is
[the catalog load](initialization.md#the-first-connection-pays-for-the-type-catalog):

```
ROME (Relational-Object Mapping Engine) open for DatabaseKey(host=localhost, port=5432, database=curia) - 421 types read in 38ms
```

It happens once per database for the lifetime of the JVM, and it is the reason a pool's first connection is measurably
slower than its siblings — so the line reports exactly how much slower.

The one thing that adds to it is your own doing: [`reloadTypes()`](type-system.md#keeping-the-catalog-fresh--reloadtypes)
produces the same line with `Reloaded` in front, every time it is called. Called once after a migration that is a line a
year; called on a schedule or per test, it is as many lines as you asked for.

Nothing else the driver says *about itself* reaches `info` — but a notice the server sends at `NOTICE`, `INFO` or `LOG`
does, on the separate logger below.

### `debug` is one line per connection

The line every other one hangs off is the connection:

```
[PID: 41288] Connected to localhost:5432/curia as 'octavius' (PostgreSQL 18.1, TLS, sslmode=verify-full)
```

It carries the `sslmode` and not just the outcome on purpose: `plaintext` on its own does not say whether that was
intended. A connection under `sslmode=disable` and one under `sslmode=prefer` that the server refused to encrypt look
identical otherwise, and only the second is worth reacting to.

Beyond that, `debug` covers the whole lifecycle: `BEGIN` / `COMMIT` / `ROLLBACK`, savepoints set, released and rolled
back to, isolation and read-only changes, cancel requests going out, connections closed and
aborted, `LISTEN` / `UNLISTEN`, listener loops starting and stopping, and COPY transfers with what the server made of
them:

```
[PID: 41288] COPY IN started: COPY senators FROM STDIN (FORMAT csv)
[PID: 41288] COPY IN finished: 4812 rows
```

It also carries the failures nothing else records — see below.

### `trace` is every statement

Two lines per statement, one on the way out and one on the way back. The statement goes on a line of its own, so a
formatted multi-line query stays readable and the parameter count does not end up looking like part of it:

```
[PID: 41288] > (2 params)
INSERT INTO senators (name, province) VALUES ($1, $2)
[PID: 41288] < 1 rows affected in 0.431ms
[PID: 41288] > (1 params)
SELECT name FROM senators WHERE province = $1
[PID: 41288] < 14 rows in 0.302ms
```

One statement has no SQL to print. `isValid()` probes a connection with an **empty query** — the cheapest round trip
the protocol allows, since PostgreSQL answers it with `EmptyQueryResponse` and nothing else — and a pool checking a
connection out lands there on every borrow:

```
[PID: 41288] > (empty query)
[PID: 41288] < done in 0.505ms
```

They are a pair on purpose. The first exists so a statement that never returns is still visible — the question "what is
it stuck on" has no other answer — and the second carries the duration and what came back. A statement that *failed*
prints only the first: the exception already carries the SQL and the parameters, and printing them again would put the
same diagnostic in the log twice.

The duration is **always milliseconds, always three decimals**, however long the statement took — `0.312ms` and
`2506.555ms` sit in the same column. Switching to seconds once a query gets slow would read a little better on that one
line and cost you the ability to sort the column or compare two lines without reading the suffix, which is what anyone
hunting a slow statement actually does. The separator is a dot regardless of the JVM's locale, for the same reason.

### Session parameters that move

A `SET` touching one of the parameters PostgreSQL reports back — `search_path`, `TimeZone`, `DateStyle`,
`default_transaction_read_only` and about a dozen others — gets a line of its own, carrying both values:

```
[PID: 41288] Session parameter changed: search_path = pg_catalog, public (was "$user", public)
```

This one is worth more than its size. A hand-written `SET search_path` changes which schema everything after it
resolves against, and [the type catalog is keyed on schema](type-system.md#where-types-come-from) — so a mapping that
worked a minute ago and now raises `TYPE_NOT_FOUND` very often has one of these lines above it and nothing else to go
on. Only real changes are reported: a parameter re-announced with the value it already had produces nothing.

The parameters that arrive during login are not reported this way — they come in one burst and are summarized on a
single line, below. And this stays at `trace` rather than `debug` for a specific reason: the driver's own
`setReadOnly()` moves `default_transaction_read_only`, so a Spring application using `@Transactional(readOnly = true)`
would otherwise get a line here for every transaction, saying what the `debug` line for that call already said.

Two things at this level are the driver talking to itself rather than running your code, and both are easy to mistake
for a problem the first time they appear:

* **The catalog query.** The statement that loads the type catalog is a statement like any other, so it is traced like
  any other — a fifteen-line `pg_catalog` join, once per database, immediately before the `info` line that summarizes
  it.
* **The startup handshake.** Five lines per connection: the TLS outcome with its protocol and cipher, the SCRAM
  mechanism, the backend process id, and the whole set of session parameters the server sent — `server_version`,
  `TimeZone`, `search_path`, `DateStyle` and a dozen more — as a single line rather than one each, since the set arrives
  in a burst and is identical on every connection to the same server.

## Server notices

Notices are the one thing that does not follow the scheme above. A `RAISE NOTICE` from a routine, a "table does not
exist, skipping" from a `DROP ... IF EXISTS` — none of it is the driver reporting on itself, it is the server talking,
and how loud it is depends on your `client_min_messages` rather than on anything here. So notices get a **logger name of
their own**, and a level taken from the server's own severity rather than from how often the line appears:

| Server severity         | Logged at |
|:------------------------|:----------|
| `WARNING`               | `warn`    |
| `NOTICE`, `INFO`, `LOG` | `info`    |
| `DEBUG`                 | `debug`   |

The separate name is what lets a codebase that leans on `RAISE NOTICE` keep them while the driver stays quiet, or the
reverse:

```xml
<logger name="io.github.octaviusframework.driver.Notice" level="WARN"/>
```

Each line carries the backend process id like everything else, so notices from different connections in a pool stay
apart. This happens whether or not you configured a handler — to *act* on a notice rather than read it, see [Handling
them yourself](initialization.md#handling-them-yourself).

## The driver does not log what it throws

A failure that reaches your code is not logged by the driver. The exception is the report, it carries far more than a
log line could — [the SQL, the parameters, and a caret under the offending
token](exceptions.md#message-format-and-logging) — and logging it here as well would duplicate every database error in
your log, once without the context of what your application was doing.

The consequence is worth stating plainly: **if you swallow an `OctaviusException`, nothing anywhere records that it
happened.** No level changes this.

```kotlin
try {
    session.createNativeQuery("INSERT INTO senators (name) VALUES ($1)").update(name)
} catch (e: OctaviusException) {
    logger.error(e) { "Failed to enrol senator" }   // pass the throwable; toString() renders the full block
}
```

The exceptions to the rule are the places where the driver **handles something itself and carries on** - a failure it
caught, or a call it made for you - because there it reaches nobody at all. Those are logged, and only those:

| What happened                                                      | Level   |
|:-------------------------------------------------------------------|:--------|
| A session could not reset its connection state on `close()`        | `warn`  |
| A session was closed with a `COPY` still in flight                 | `warn`  |
| Auto-commit could not be restored after a successful commit        | `warn`  |
| A `NoticeHandler` you configured threw                             | `error` |
| `isValid()` answered `false` — with the reason it did              | `debug` |
| A cancel request could not be opened or sent                       | `debug` |
| A large object descriptor could not be closed                      | `debug` |
| A network timeout could not be restored after a probe or poll loop | `debug` |
| A socket refused to close, or `Terminate` could not be sent        | `trace` |

The first four are at their levels because somebody has to see them. Every `warn` line is a connection on its way out
with the reason held nowhere else: a pool evicting one reports that it went away and nothing more - whether the
session could not reset it or was closed with a transfer still open - and a scope whose commit landed before the
connection dropped tells the caller nothing at all - deliberately, since the work is in the database and raising there
would read as a transaction that failed. The `error` is your own code, and nothing else in the process will mention
it.

## What never reaches the log

Two things are absent at **every** level, including `trace`, and no setting turns them on:

* **Passwords**, and every SCRAM intermediate — the client proof, the salted password, the server signature.
* **Anything a large object carried.**

What *is* present, and worth knowing before you turn a level up in production:

* `debug` writes the **username** each connection authenticates as, and the **SQL of every COPY statement**.
* `trace` writes the **SQL of every statement**. Literals you inline into a query string are values as much as bound
  parameters are — this is one more reason to bind them
  ([and there are others](queries.md#two-ways-to-pass-parameters)).

### Parameter values are a property, not a level

By default a statement logs how many parameters it carried and nothing about them. The values become part of the
traced line only when [`logParameterValues`](initialization.md#network-and-limits) is set:

```
[PID: 41288] > ($1=Marcus Tullius, $2=42, $3=ByteArray(4000000 bytes))
INSERT INTO senators (name, century, seal) VALUES ($1, $2, $3)
```

That it is a property and not simply a consequence of `trace` is deliberate. Raising a log level is an operational
decision — taken during an incident, often across a whole deployment, by whoever owns the logging config. Writing the
contents of your tables into a file that will be shipped to an aggregator and kept for months is a decision about
data. Those two are rarely the same person's to make, so they are not the same switch: turning the driver up to see
which statement is slow does not start recording who it was about.

The rendering is bounded whether it lands in a log or an exception, because both go through the same formatter — and it
is bounded **as it is built**, not trimmed afterwards. That distinction matters here more than it looks: a
[bulk write](bulk-writes.md) passes one array per column, so a parameter holding ten thousand elements is the
documented idiom rather than an abuse. Rendering it in full and then keeping a hundred characters would assemble
megabytes to throw almost all of them away, on the path where something has already gone wrong.

So a `ByteArray` is named rather than dumped, a long string is copied only as far as it is needed, and a collection,
array or map is walked element by element until the budget runs out — `[0, 1, 2, … +9990 more]`.

> [!NOTE]
> One thing is not bounded, and cannot be: a class the driver does not know renders through its own `toString()`, which
> is a single opaque call. Most DTOs are small and worth reading, so it is still called — but a `toString()` that
> assembles something enormous is outside what the driver can do anything about.

> [!NOTE]
> This cuts the other way too, and is not a promise the log makes: `OctaviusException` carries parameter values
> regardless of the property, since the exception goes to the caller rather than to a file. See [Query
> Context](exceptions.md#query-context) for what an exception you log yourself will print.

## Following one connection

Every line the driver writes about a connection is prefixed with the backend process id:

```
[PID: 41288] Transaction committed; new transaction started
```

That number is `pg_stat_activity.pid`, and it is what PostgreSQL's own log prefixes its lines with under
`log_line_prefix = '%p'`. It is the only identifier the driver log, the server log and the catalog view have in common,
so it is the join key for all three — which backend ran the statement, what it was waiting on, and what the server
thought of it.

The prefix is written into the message rather than put in the MDC deliberately. Sessions here move across virtual
threads and coroutine dispatchers, and MDC does not follow them; a value in the message does.

## What tracing costs

Turning `trace` on is not free — it writes two lines per statement, and at a few thousand statements a second that is a
real load on your appender. That is the cost you are choosing.

What it costs when it is **off** is nothing you can measure. A statement running with tracing disabled reads the level
once and stops: it does not read the clock, does not build a message, and allocates nothing — held to that by
[`InsertBenchmark`](performance.md#what-is-measured), which runs ten thousand single statements per operation and so
turns any per-statement cost into a visible one.

The one thing tracing never does is descend below the statement. Nothing is logged per row, per column or per protocol
message, at any level — a per-row level check would cost more on a large result than everything else on this page put
together.

## Configuration recipes

**Out of the box**, with no configuration at all, you get the `info` line per database — and every notice the server
sends, since those arrive at the severity it chose. On a schema that raises none, that is the one line.

**Diagnosing a connection or pool problem** — why connections are being evicted, whether TLS is really on, what a
transaction actually did:

```xml
<logger name="io.github.octaviusframework.driver" level="DEBUG"/>
```

**Seeing statements and their timings**, without the rest of the lifecycle:

```xml
<logger name="io.github.octaviusframework.driver.execution.QueryExecutor" level="TRACE"/>
```

**Notices only**, with the driver otherwise quiet — for a codebase that leans on `RAISE NOTICE`:

```xml
<logger name="io.github.octaviusframework.driver" level="WARN"/>
<logger name="io.github.octaviusframework.driver.Notice" level="DEBUG"/>
```

The notice logger is independent of everything else on purpose — see [Server notices](#server-notices).

**Down to failures only** — the driver's own `warn` and `error`, and nothing routine:

```xml
<logger name="io.github.octaviusframework.driver" level="WARN"/>
```

That is not silence: a server `WARNING` still arrives, because the notice logger sits under the same name and inherits
the threshold. Add this to stop those as well, and the driver has nothing left to say short of a failure:

```xml
<logger name="io.github.octaviusframework.driver.Notice" level="OFF"/>
```
