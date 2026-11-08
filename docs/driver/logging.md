# Logging

*The vigiles walked their rounds every night of the year and filed nothing when the streets were quiet; a report existed
because something had happened, not because anyone had been watching. Raising a level here works the same way — it does
not make the driver more careful, it only asks it to write down rounds it was already walking. What changes between
`info` and `trace` is how much of that walking you have agreed to read.*

Octavius logs through [SLF4J](https://www.slf4j.org/), so it writes wherever the rest of your application already
writes. Levels are set in your logging config, not on the driver; the one driver property is
[`logParameterValues`](#parameter-values-are-a-property-not-a-level).

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

The driver depends on `slf4j-api` and nothing else. SLF4J with no backend on the classpath prints one warning at startup
and then discards everything, so a driver that is silent at every level is usually a missing dependency:

```kotlin
runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
```

Spring Boot's `spring-boot-starter` already brings Logback, so an application built on
[the Spring integration](spring-integration.md) has one.

## Logger names

Every name is the fully-qualified class it comes from, except `Notice`, which carries server notices under a name of
their own.

| Logger                                                                     | What it carries                                                                                       |
|:---------------------------------------------------------------------------|:------------------------------------------------------------------------------------------------------|
| `io.github.octaviusframework.driver.Notice`                                | Server notices, at a level mirroring the server's own severity                                        |
| `io.github.octaviusframework.driver.jdbc.OctaviusConnectionFactory`        | One line per physical connection opened                                                               |
| `io.github.octaviusframework.driver.jdbc.OctaviusConnection`               | Transactions, savepoints, isolation, cancellation, abort, validation probes                           |
| `io.github.octaviusframework.driver.session.OctaviusSessionImpl`           | Session close and abort, and a close that evicted its connection instead of returning it              |
| `io.github.octaviusframework.driver.transaction.TransactionManager`        | Auto-commit left unrestored after a transaction scope committed                                       |
| `io.github.octaviusframework.driver.execution.QueryExecutor`               | Every statement and its duration                                                                      |
| `io.github.octaviusframework.driver.registry.GlobalCatalogStore`           | The type catalog load, and every explicit reload of it                                                |
| `io.github.octaviusframework.driver.registry.CodecDictionary`              | A registered codec the catalog it was built against leaves bound to nothing                           |
| `io.github.octaviusframework.driver.copy.CopyManager`                      | COPY transfers, with row and byte counts                                                              |
| `io.github.octaviusframework.driver.notification.NotificationManager`      | `LISTEN` / `UNLISTEN` and the listener loops                                                          |
| `io.github.octaviusframework.driver.ssl.SslNegotiator`                     | Whether the connection ended up encrypted, and under which cipher                                     |
| `io.github.octaviusframework.driver.auth.Authenticator`                    | Handshake steps and session parameters                                                                |
| `io.github.octaviusframework.driver.auth.ScramSha256Authenticator`         | The SCRAM mechanism actually negotiated                                                               |
| `io.github.octaviusframework.driver.io.PgStream`                           | Session parameters that change, socket close, protocol messages ignored, a `NoticeHandler` that threw |
| `io.github.octaviusframework.driver.lo.LargeObject`                        | A descriptor that could not be closed                                                                 |
| `io.github.octaviusframework.driver.spring.OctaviusJdbcTransactionManager` | A rollback on a connection that had already gone                                                      |

All of them sit under `io.github.octaviusframework.driver`, so one entry configures the lot. The last one ships in the
separate `driver-spring-integration` artifact.

[`migrations`](../migrations/logging.md) logs under names of its own, and the client writes nothing at all — see
[Logging](../client/README.md#logging) there.

## What each level says

The split is by how often a line appears, not by how important it sounds.

| Level   | Frequency                                      | What you get                                                                                            |
|:--------|:-----------------------------------------------|:--------------------------------------------------------------------------------------------------------|
| `error` | Practically never                              | Only a `NoticeHandler` of yours that threw                                                              |
| `warn`  | Practically never                              | A connection on its way out, a registered codec bound to nothing, or a listed server of another cluster |
| `info`  | Once per database, plus every `reloadTypes()`  | The type catalog load, with its type count and duration                                                 |
| `debug` | Once per connection, per transaction, per COPY | Lifecycle: connections, transactions, savepoints, `LISTEN`, TLS, transfers                              |
| `trace` | Twice per statement                            | Every statement, its duration, and session parameters that move                                         |

The table is the driver reporting on itself. [Server notices](#server-notices) take their level from the server's
severity instead, so a schema that raises `NOTICE`s or `WARNING`s adds to `info` and `warn`.

### `info` is the catalog

The driver's only unprompted line is [the catalog load](initialization.md#the-first-connection-pays-for-the-type-catalog),
once per database for the lifetime of the JVM. It is also how much slower a pool's first connection was:

```
ROME (Relational-Object Mapping Engine) open for localhost:5432/curia - 421 types read in 38ms
```

[`reloadTypes()`](type-system.md#keeping-the-catalog-fresh--reloadtypes) writes a line of its own every time it is
called:

```
ROME rebuilt for localhost:5432/curia - 423 types re-read in 31ms
```

### `debug` is one line per connection

The line every other one hangs off is the connection:

```
[PID: 41288] Connected to localhost:5432/curia as 'octavius' (PostgreSQL 18.1, TLS, sslmode=verify-full)
```

The `sslmode` sits next to the outcome because `plaintext` alone does not say whether that was intended: under
`sslmode=prefer` it means the server refused to encrypt.

Beyond that, `debug` covers the lifecycle: `BEGIN` / `COMMIT` / `ROLLBACK`, savepoints, isolation and read-only changes,
cancel requests, connections closed and aborted, `LISTEN` / `UNLISTEN`, listener loops starting and stopping, and COPY
transfers:

```
[PID: 41288] COPY IN started:
COPY senators FROM STDIN (FORMAT csv)
[PID: 41288] COPY IN finished: 4812 rows
```

It also carries the failures nothing else records — see
[The driver does not log what it throws](#the-driver-does-not-log-what-it-throws).

### `trace` is every statement

Two lines per statement, one on the way out and one on the way back, with the SQL on a line of its own:

```
[PID: 41288] > (2 params)
INSERT INTO senators (name, province) VALUES ($1, $2)
[PID: 41288] < 1 rows affected in 0.431ms
[PID: 41288] > (1 params)
SELECT name FROM senators WHERE province = $1
[PID: 41288] < 14 rows in 0.302ms
```

`isValid()` probes a connection with an empty query, which is what a pool checking a connection out shows up as:

```
[PID: 41288] > (empty query)
[PID: 41288] < done in 0.505ms
```

A statement that has not come back shows only its `>` line, which is how you see what a connection is stuck on. So does
a statement that failed, since the exception already carries the SQL and the parameters.

The duration is always milliseconds with three decimals and a dot, whatever the JVM's locale, so `0.312ms` and
`2506.555ms` sort and compare as one column.

### Session parameters that move

A `SET` touching one of the parameters PostgreSQL reports back — `search_path`, `TimeZone`, `DateStyle`,
`default_transaction_read_only` and about a dozen others — gets a line carrying both values:

```
[PID: 41288] Session parameter changed: search_path = pg_catalog, public (was "$user", public)
```

Only real changes are reported, and not the burst that arrives during login. A `TYPE_NOT_FOUND` that appears
mid-session often has a `search_path` line above it.

Two things at this level are the driver's own work rather than your code:

* **The catalog query**, a long `pg_catalog` join once per database, right before the `info` line.
* **The startup handshake**, a few lines per connection: the TLS protocol and cipher, the SCRAM mechanism, the protocol
  version where one was negotiated, the backend process id, and the session parameters the server sent, on one line.

## Server notices

A `RAISE NOTICE` from a routine or a "does not exist, skipping" from `DROP ... IF EXISTS` is the server talking, and how
much of it arrives depends on your `client_min_messages`. Notices go to the `io.github.octaviusframework.driver.Notice`
logger, at a level taken from the server's severity:

| Server severity         | Logged at |
|:------------------------|:----------|
| `WARNING`               | `warn`    |
| `NOTICE`, `INFO`, `LOG` | `info`    |
| `DEBUG`                 | `debug`   |

Each line carries the backend process id. They are logged whether or not you configured a handler; to act on a notice
in code, see [Handling them yourself](initialization.md#handling-them-yourself).

## The driver does not log what it throws

A failure that reaches your code is not logged by the driver. The exception is the report, and it carries
[the SQL, the parameters and a caret under the offending token](exceptions.md#message-format-and-logging). **If you
swallow an `OctaviusException`, nothing anywhere records that it happened**, at any level.

What the driver logs is what it handled itself and carried on from, because that reaches nobody else:

| What happened                                                      | Level   |
|:-------------------------------------------------------------------|:--------|
| A session could not reset its connection state on `close()`        | `warn`  |
| A session was closed with a `COPY` still in flight                 | `warn`  |
| Auto-commit could not be restored after a successful commit        | `warn`  |
| A listed server belongs to another cluster than the type catalog   | `warn`  |
| A session was closed after its connection had gone back            | `warn`  |
| A `NoticeHandler` you configured threw                             | `error` |
| A listed server was passed over — with why                         | `debug` |
| `isValid()` answered `false` — with the reason it did              | `debug` |
| A cancel request could not be opened or sent                       | `debug` |
| A large object descriptor could not be closed                      | `debug` |
| A network timeout could not be restored after a probe or poll loop | `debug` |
| A socket refused to close, or `Terminate` could not be sent        | `trace` |

The first three `warn` lines are a connection on its way out: a pool evicting it says only that it went, so the reason
is here. The fourth is a server [passed over](initialization.md#one-catalog-for-the-list) on every connection, which
nothing else reports while another server in the list answers.

## What never reaches the log

Two things are absent at every level, including `trace`:

* **Passwords**, and every SCRAM intermediate — the client proof, the salted password, the server signature.
* **Anything a large object carried.**

What is present, and worth knowing before turning a level up in production:

* `debug` writes the **username** each connection authenticates as, and the **SQL of every COPY statement**.
* `trace` writes the **SQL of every statement**, including any literals inlined into it
  ([one more reason to bind them](queries.md#two-ways-to-pass-parameters)).

### Parameter values are a property, not a level

A traced statement carries the count of its parameters and nothing about them. The values appear only with
[`logParameterValues`](initialization.md#network-and-limits) set, so turning the driver up to find a slow statement does
not start recording the data in it:

```
[PID: 41288] > ($1=Marcus Tullius, $2=42, $3=ByteArray(4000000 bytes))
INSERT INTO senators (name, century, seal) VALUES ($1, $2, $3)
```

Each value is rendered within a budget, without building the whole of it first: a `ByteArray` is named rather than
dumped, a long string is cut, and a collection, array or map is walked until the budget runs out —
`[0, 1, 2, … +9990 more]`. A
class the driver does not know renders through its own `toString()`, which the budget cannot reach into.

An `OctaviusException` carries the values whatever the property says — see [Query context](exceptions.md#query-context).

## Following one connection

Every line the driver writes about a connection is prefixed with the backend process id:

```
[PID: 41288] COMMIT sent; the next statement begins the next transaction
```

That number is `pg_stat_activity.pid`, and what PostgreSQL's own log prints under `log_line_prefix = '%p'`, so it joins
the driver's log, the server's log and the view. It is in the message rather than in the MDC, so it follows a session
across virtual threads and coroutine dispatchers.

## What tracing costs

With `trace` on, every statement writes two lines, which at a few thousand statements a second is real load on your
appender. With it off, a statement reads the level once and stops: no clock read, no message, no allocation.

Nothing is logged per row or per column, at any level.

## Configuration recipes

**At `INFO`**, where most configurations leave the root logger, you get the `info` line per database and every notice
the server sends.

**Diagnosing a connection or pool problem** — why connections are evicted, whether TLS is really on, what a transaction
did:

```xml
<logger name="io.github.octaviusframework.driver" level="DEBUG"/>
```

**Statements and their timings**, without the rest of the lifecycle:

```xml
<logger name="io.github.octaviusframework.driver.execution.QueryExecutor" level="TRACE"/>
```

**Notices only**, with the driver otherwise quiet:

```xml
<logger name="io.github.octaviusframework.driver" level="WARN"/>
<logger name="io.github.octaviusframework.driver.Notice" level="DEBUG"/>
```

**Failures only** — the driver's own `warn` and `error`. A server `WARNING` still arrives, since the notice logger
inherits the threshold:

```xml
<logger name="io.github.octaviusframework.driver" level="WARN"/>
```

To stop those as well:

```xml
<logger name="io.github.octaviusframework.driver.Notice" level="OFF"/>
```
