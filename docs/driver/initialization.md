# Session Initialization and Configuration

*A governor leaving for his province carried one sealed set of instructions — the mandata. He might sail from Ostia,
ride the Via Appia, or take ship at Brundisium; the document was the same whichever road he took, and nothing he did out
there went beyond what it said. `OctaviusProperties` is that document, and everything below is only a different road to
the same province.*

Every connection Octavius opens is configured through one strongly-typed class — `OctaviusProperties`. Everything else on this page is a different route to filling it in: native factory functions take it directly, `OctaviusDataSource` exposes its common fields as bean properties, and `DriverManager` parses it out of a JDBC URL. String keys from a URL or a `java.util.Properties` object are matched case-insensitively against its fields; anything it does not recognize is kept aside and [sent to the server as a startup parameter](#startup-parameters).

## Getting a session

| Entry point                               | Reach for it when                                                     |
|:------------------------------------------|:----------------------------------------------------------------------|
| `getOctaviusSession(properties)`          | Native, typed, no pool. The simplest path in Kotlin code.             |
| `getOctaviusSession(url, user, password)` | You already have a URL and just need credentials alongside it.        |
| `getOctaviusSession(url, properties)`     | A URL supplies the address, typed properties supply the rest.         |
| `dataSource.getOctaviusSession()`         | Anything behind a `DataSource` — including a HikariCP pool.           |
| `connection.getOctaviusSession()`         | You are handed a `java.sql.Connection` and want the native API on it. |

### 1. Typed properties

No URL involved, everything type-checked:

```kotlin
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.ssl.SslMode

val properties = OctaviusProperties().apply {
    serverName = "localhost"
    portNumber = 5432
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"
    loginTimeout = 10
    sslMode = SslMode.REQUIRE
    applicationName = "LegioXIII-App"

    // Anything the driver does not know becomes a server startup parameter
    additionalProperties["search_path"] = "castra, public"
}

val session = getOctaviusSession(properties)
```

If a URL carries part of the configuration, the `getOctaviusSession(url, properties)` overload parses it first and then merges the typed properties on top — so the typed values win on any field set in both.

### 2. `DriverManager`

The driver registers itself with `java.sql.DriverManager`, so a URL (optionally with a `Properties` object) is enough:

```kotlin
import java.sql.DriverManager
import io.github.octaviusframework.driver.jdbc.getOctaviusSession

val url = "jdbc:octavius://localhost:5432/res_publica?user=consul&password=senatus_populusque"
val connection = DriverManager.getConnection(url)

val session = connection.getOctaviusSession()
```

An IPv6 address with a port goes in brackets, the way libpq writes it — `jdbc:octavius://[::1]:5432/res_publica`; with no port they can be left off. `serverName` reads an address the same way, and a single server's comes back bare: `serverName` is `::1`, and the port is in `portNumber`.

### 3. `OctaviusDataSource`

`OctaviusDataSource` bypasses `DriverManager` and takes typed values directly:

```kotlin
import io.github.octaviusframework.driver.jdbc.OctaviusDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession

val dataSource = OctaviusDataSource().apply {
    serverName = "localhost"
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"
    socketTimeout = 30
    applicationName = "LegioXIII-App"

    // Anything without an accessor of its own, startup parameters included
    setProperty("search_path", "castra, public")
}

val session = dataSource.getOctaviusSession()
```

Every field of `OctaviusProperties` has an accessor here, so nothing is reachable only through a URL. The `url` property is the other way in: it parses a whole URL into the same configuration, and the two combine — `url = "jdbc:octavius://localhost:5432/res_publica?maxCachedRowSize=8192"` beside `user` and `password`.

> [!NOTE]
> Reading `dataSource.url` back renders the current configuration as a URL, but **never the password** — that string is the one most likely to reach a log. The password stays readable through `dataSource.password`, and `OctaviusProperties.copy()` is the lossless way to duplicate a full configuration.

## Adding HikariCP Connection Pooling

Octavius implements `java.sql.Connection` so that a pool such as [HikariCP](https://github.com/brettwooldridge/HikariCP) can manage it. HikariCP takes the driver three ways, and in Kotlin code the first is the one to reach for.

### Via a configured instance

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.OctaviusDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.ssl.SslMode

val octavius = OctaviusDataSource().apply {
    serverName = "localhost"
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"
    sslMode = SslMode.REQUIRE
    applicationName = "curia-api"
}

val pool = HikariDataSource(HikariConfig().apply {
    dataSource = octavius
    maximumPoolSize = 10
})

val session = pool.getOctaviusSession() // borrows a session
```

HikariCP uses that object as it is. Nothing is set by name or converted, so every accessor works at its real type, an enum or a [`noticeHandler`](#handling-them-yourself) instance included, and the compiler checks the configuration.

### Via `dataSourceClassName`

Where the configuration comes from a properties file or from Spring's `spring.datasource.hikari.data-source-properties`, HikariCP builds the data source itself and sets each property by name:

```kotlin
val config = HikariConfig().apply {
    dataSourceClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDataSource"
    addDataSourceProperty("serverName", "localhost")
    addDataSourceProperty("databaseName", "res_publica")
    addDataSourceProperty("user", "consul")
    addDataSourceProperty("password", "senatus_populusque")
    addDataSourceProperty("socketTimeout", "30")
    maximumPoolSize = 10
}
```

The name has to match an accessor on `OctaviusDataSource` — `sslMode`, not libpq's `sslmode` — and one it does not know fails the pool at startup rather than being ignored. Every property in the [configuration reference](#configuration-reference) has one and takes a string, except:

* the four enums — `sslMode`, `channelBinding`, `targetSessionAttrs` and `loadBalanceHosts` — and `noticeHandler`, which HikariCP passes on unconverted: a string fails the pool with *"argument type mismatch"*, and only the object itself goes through;
* startup parameters, which have no accessor by design, `application_name` aside.

From a properties file, the enums and startup parameters go into the `url` property, which takes a whole URL, and a handler goes in by class name as `noticeHandlerClassName`:

```kotlin
addDataSourceProperty("url", "jdbc:octavius://localhost:5432/res_publica?sslmode=require&search_path=castra")
```

### Via JDBC URL

```kotlin
val pool = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:octavius://localhost:5432/res_publica"
    username = "consul"
    password = "senatus_populusque"
    maximumPoolSize = 10
})
```

### Failures, closing and auto-commit

Constructing the pool opens a connection to check the configuration, so a driver failure surfaces there as HikariCP's own `PoolInitializationException`, with the driver's exception as its cause: `e.findOctaviusCause() ?: e` gets it, unwrapping a `SQLExceptionWrapper` where it finds one. A borrow needs none of that: `getOctaviusSession()` already throws the `InitializationException`.

Closing a session borrowed from a pool returns its connection rather than shutting it down, and anything asked of the session afterwards is refused with `InvalidOperationException(RESOURCE_CLOSED)` — as it is when `connection.close()` sent the connection back first. Close the session first: if the connection went back before it, the session's own `close()` leaves the connection alone, since it may be the next borrower's by now, and whatever the session left on it stays there.

Leave the pool's auto-commit at its default, `true`. Switching it costs this driver no round trip, so turning it off saves nothing, and it puts every borrowed session inside a transaction whose work is rolled back if the session closes without committing — see [Transactions](transactions.md#manual-control).

## What survives a return to the pool

A pooled connection outlives the session you borrowed it through, so whatever is left set on it becomes the next borrower's starting state. Some of that is cleaned up for you:

| Connection state                                                               | Undone when the session closes?                                              |
|:-------------------------------------------------------------------------------|:-----------------------------------------------------------------------------|
| `autoCommit`, `readOnly`, `transactionIsolationLevel`                          | Yes — HikariCP tracks these through its own proxy and restores its defaults. |
| A `COPY` the caller never finished                                             | Yes — by evicting the connection instead of handing it back.                 |
| `LISTEN` registrations made via `notifications.listen`                         | Yes — the session issues `UNLISTEN *` if it subscribed to anything.          |
| A transaction left open — never committed, or opened by a hand-written `BEGIN` | Yes — rolled back, since the driver cannot know what that work was for.      |
| **Anything else you set by running the SQL yourself**                          | **No.**                                                                      |

Neither the pool nor the driver parses the statements you send, so neither can know that a `SET search_path`, a `SET statement_timeout`, a `SET SESSION CHARACTERISTICS AS TRANSACTION ...`, a hand-written `LISTEN` or a temporary table ever happened: all of it stays on the connection for the next borrower. The typed API is the tracked path — `session.transactionIsolationLevel`, `session.notifications.listen` — and a per-connection default belongs in a [startup parameter](#startup-parameters), which every connection starts with, so there is nothing to restore.

Queries themselves leave nothing behind: the driver executes through unnamed statements and portals, so nothing accumulates server-side from ordinary traffic.

## What happens when a session opens

**PostgreSQL 18 or newer is required, and the server is checked once the login succeeds.** Anything older gets the connection closed and an `InitializationException(UNSUPPORTED_SERVER_VERSION)`, naming the version received. So does a login that did not report `search_path`, with `MISSING_PROTOCOL_PARAMETER` — PostgreSQL 18 always reports it, so it is missing only when a pooler or a proxy in between did not pass it on. With [several servers](#several-servers) listed, this is also where one that is not the kind `target_session_attrs` asks for is logged out of, and the next one tried.

**The first connection to a database also loads its type catalog** — [below](#the-first-connection-pays-for-the-type-catalog).

### Authentication is SCRAM-SHA-256, or a password inside TLS

| The server asks for                                              | Result                                                                                     |
|:-----------------------------------------------------------------|:-------------------------------------------------------------------------------------------|
| SCRAM-SHA-256-PLUS                                               | Connected, bound to the TLS channel.                                                       |
| SCRAM-SHA-256-PLUS, its certificate signed with Ed25519 or Ed448 | `InitializationException(UNSUPPORTED_MECHANISM)`; see [Channel binding](#channel-binding). |
| SCRAM-SHA-256                                                    | Connected.                                                                                 |
| Nothing — `trust`, `cert`                                        | Connected.                                                                                 |
| A cleartext password, over TLS                                   | Connected; the password goes out as it is, inside TLS.                                     |
| A cleartext password, in plaintext                               | `InitializationException(UNSUPPORTED_PASSWORD_ENCRYPTION)`                                 |
| MD5                                                              | `InitializationException(UNSUPPORTED_PASSWORD_ENCRYPTION)`                                 |
| GSSAPI, SSPI, anything else                                      | `InitializationException(UNSUPPORTED_MECHANISM)`                                           |

**The cleartext row is the one worth reading twice.** The server asks for a cleartext password when `pg_hba.conf` names `password`, `ldap`, `pam` or `radius`, and the password then travels as typed, with only the encryption underneath to protect it. So the driver insists on that encryption rather than trusting the deployment to have arranged it: on an encrypted connection the exchange proceeds, on one that ended up in plaintext the password is never sent, and `sslmode=require` or above makes sure it is the first. `channelBinding=require` refuses the exchange outright, before the password leaves the JVM, because no cleartext login can be bound to the channel.

The server asks for MD5 only under an `md5` line for a role whose password is still stored as an MD5 hash, one set while `password_encryption` was `md5`. `ALTER ROLE … PASSWORD …` stores it again as SCRAM, PostgreSQL 18's default, and the same line then asks for SCRAM.

### Channel binding

Under `sslmode=require` the connection is encrypted and nothing checks who is on the other end of it: the driver accepts whatever certificate the server presents. Channel binding closes that gap. The SCRAM proof is computed over a hash of the certificate on the wire, so an intermediary that terminated TLS with a certificate of its own cannot relay the exchange.

How hard the driver insists is the `channelBinding` property:

| Value     | Behaviour                                                                |
|:----------|:-------------------------------------------------------------------------|
| `prefer`  | Bind when the connection is encrypted and the server offers it. Default. |
| `require` | Refuse to log in at all unless the exchange was bound.                   |
| `disable` | Never offer it; the exchange declares no support for binding.            |

PostgreSQL offers `SCRAM-SHA-256-PLUS` on every encrypted connection, so under the default every TLS connection to it ends up bound. What `require` adds is the failure: without TLS, or against a server that does not offer binding, you get an `InitializationException(UNSUPPORTED_MECHANISM)` rather than a quietly unbound login. It also rejects a login the server waved through without asking, since `trust` is not a bound exchange either.

A certificate signed with Ed25519 or Ed448 is the exception. The hash is taken with the digest the certificate was signed with, and those signatures use none, so neither end can compute it. The driver refuses such a login under `prefer` as under `require`, before the exchange begins, with an `InitializationException(UNSUPPORTED_MECHANISM)` naming the signature, since unbound it could be relayed by an intermediary presenting a certificate like that of its own. `channelBinding=disable` logs in, unbound.

Binding hashes the certificate; it does not judge it. That still belongs to [`verify-full`](#ssl): binding proves the exchange reached the holder of *that* certificate, `verify-full` that the certificate is the one you meant to reach.

> [!NOTE]
> A `SCRAM channel binding negotiation error` from the server means something between you and it rewrote the mechanism list: on an encrypted connection where binding was not offered, the driver says so in the handshake, and PostgreSQL, which always offers it over TLS, reads that as the contradiction it is.

### The first connection pays for the type catalog

The catalog is read once per database in the JVM, and every later connection to that database, from any pool, reuses it. So the first connection a pool opens is measurably slower than its siblings, and pre-warming one connection at startup moves that cost out of your first request. What is loaded, how catalogs are keyed and shared, and how to refresh one after a migration are in [Type System](type-system.md#where-types-come-from).

## Several servers

The address can list several servers, the way `libpq` does — each with a port of its own, or the one `portNumber` gives the rest:

```kotlin
val url = "jdbc:octavius://db1:5432,db2:5432,db3/res_publica?target_session_attrs=read-write"
```

`serverName` takes the same list — `"db1,db2:5433,[::1]:5434"` — and so does a `host` parameter. The servers are tried in turn until one becomes a connection, and so is every address each name resolves to.

**A server is chosen when a connection opens**, and an open session never moves. A pool replaces a connection whose server went away, and the replacement goes through the list again.

### Which server: `target_session_attrs`

| `TargetSessionAttrs`                | Settles for                                                                                |
|:------------------------------------|:-------------------------------------------------------------------------------------------|
| `ANY` (`any`)                       | The first server that accepts the connection. Default.                                     |
| `READ_WRITE` (`read-write`)         | One whose sessions accept writes: not in hot standby, `default_transaction_read_only` off. |
| `READ_ONLY` (`read-only`)           | The converse: in hot standby, or `default_transaction_read_only` on.                       |
| `PRIMARY` (`primary`)               | One not in hot standby.                                                                    |
| `STANDBY` (`standby`)               | One in hot standby.                                                                        |
| `PREFER_STANDBY` (`prefer-standby`) | A standby where the list has one; where it has none, a second pass takes any server.       |

The values and their meaning are `libpq`'s. A server's kind is read from what it reports at login — `in_hot_standby`, and `default_transaction_read_only` for the two read/write modes. A server that does not report what the setting needs is refused with `MISSING_PROTOCOL_PARAMETER`, the same as one that does not report `search_path`.

### In what order: `load_balance_hosts`

`disable`, the default, tries the servers as listed and each one's addresses in the order its name resolves to. `random` shuffles both, afresh for every connection — which spreads a pool's connections over every server `target_session_attrs` accepts.

### What moves the search on

| At a server                                                                                                  | The search                     |
|:-------------------------------------------------------------------------------------------------------------|:-------------------------------|
| Nothing answers at the address, or not within `loginTimeout`                                                 | Goes on to the next address    |
| It takes no connections now — starting up, shutting down, in recovery (`57P03`)                              | Goes on to the next server     |
| It is not the kind `target_session_attrs` asks for                                                           | Goes on to the next server     |
| It belongs to another cluster than the catalog — [below](#one-catalog-for-the-list)                          | Goes on to the next server     |
| Anything else — a rejected password, a TLS failure, a database that does not exist, PostgreSQL older than 18 | Ends, and that error is thrown |

What the last row covers would fail the same way on every server. `loginTimeout` is spent per address, so three servers that do not answer can take three times as long.

Where nothing in the list could be used, the `InitializationException(CONNECTION_ERROR)` lists every server and address tried and why each was passed over, and carries their exceptions as suppressed. A single server at a single address fails exactly as it would with nothing to choose between.

### One catalog for the list

The servers of one list are one database: they share one [type catalog](#the-first-connection-pays-for-the-type-catalog), and with it every converter and registration, whichever server a connection lands on and in whatever order the list names them.

That is correct only while they are one cluster. The catalog maps types by OID, and only a physical standby is sure to have its primary's. So before a server of a list is used for the first time, the driver asks it for its `system_identifier`; one from another cluster — a logical replica, another node of a multi-master set — is never used, so it is asked again and passed over, with a warning in the log, every time the search reaches it. Servers like that need a data source each.

## Startup parameters

Any property the driver does not recognize is passed through to PostgreSQL in the startup message, which makes `additionalProperties` the place to set server-side session defaults at connect time — no `SET` round trip afterwards:

```kotlin
val properties = OctaviusProperties().apply {
    // ... address and credentials ...
    additionalProperties["search_path"] = "castra, public"
    additionalProperties["statement_timeout"] = "5000"
}
```

The same works through a URL — `?search_path=castra` — since unknown query parameters land in the same place.

**`application_name` is the one with a property of its own**, so that a pool or a properties file reaches it without a URL. `applicationName` and PostgreSQL's own `application_name` fill the same field, and the property wins over an `application_name` put into `additionalProperties` by hand. Set in neither place, the connection reports **`Octavius Driver`**, so `pg_stat_activity` says at least which driver opened it; naming your own application says who it is for.

**Character encoding is the one exception you cannot override**: the driver always declares UTF-8 to the server, and there is no plan to support anything else, so it is not exposed as a setting.

## Notices from the server

PostgreSQL sends notices alongside ordinary traffic — a `RAISE NOTICE` from a routine, a "table does not exist, skipping" from a `DROP ... IF EXISTS`, a deprecation warning. They are not errors and nothing is interrupted by one.

Octavius pushes them rather than collecting them as [`SQLWarning`](octavius-vs-jdbc.md#what-answers-quietly-instead-of-throwing): every notice is logged as it arrives, under [a logger name of its own](logging.md#server-notices), and handed to a `NoticeHandler` if you configured one. Nothing accumulates and nothing needs draining.

### Handling them yourself

Implement `NoticeHandler` and name the class in the [`noticeHandlerClassName` property](#network-and-limits):

```kotlin
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.notice.PgNotice

object AuditNoticeHandler : NoticeHandler {
    override fun handleNotice(notice: PgNotice) {
        if (notice.severity == "WARNING") {
            metrics.counter("db.warnings", "code", notice.code).increment()
        }
    }
}
```

```kotlin
properties.noticeHandlerClassName = "com.example.AuditNoticeHandler"
// or on the URL: ?noticeHandlerClassName=com.example.AuditNoticeHandler
```

A Kotlin `object` is reused as a singleton across every connection; a class is instantiated per connection through its no-arg constructor.

A handler that needs something passed to its constructor, such as a metrics registry in place of the global `metrics` above, is built in code and set as `noticeHandler`, on `OctaviusProperties` or on an `OctaviusDataSource`:

```kotlin
class AuditNoticeHandler(private val metrics: MeterRegistry) : NoticeHandler { /* as above */ }

dataSource.noticeHandler = AuditNoticeHandler(meterRegistry)
```

Every connection opened with it shares that one instance, and it takes the place of `noticeHandlerClassName` when both are set. A URL cannot carry an instance, so a pool reaches one only through [a configured data source](#via-a-configured-instance) or as the object given to [`addDataSourceProperty`](#via-datasourceclassname); one that builds the driver from a URL, as Spring Boot does, gets the class name or nothing.

A shared handler — an `object` or an instance — is called from the thread of every connection that uses it, at the same time, so whatever it keeps has to be safe for that.

`PgNotice` carries every field a notice can hold, under the same names `ServerErrorMessage` uses: `severity`, `code` and `message` always arrive, `file`, `line` and `routine` come with every notice too, and `detail`, `hint`, `schema`, `table`, `column`, `datatype` and `constraint` are what a `RAISE ... USING` fills in. The comparison above is safe because `severity` is read from the non-localized field — the translated severity a non-English `lc_messages` produces is kept apart, as `localizedSeverity`.

It also carries `processId`, the backend process id the log line is prefixed with, which is how a shared handler tells the connections of a pool apart. The driver takes it from the startup handshake, so a notice raised before that finished reports `-1`; at the default verbosity none is. It is not the same number as `PgNotification.processId`, which names the *foreign* backend that ran the `NOTIFY`.

> [!WARNING]
> **`handleNotice` runs synchronously on the connection's network thread**, in the middle of reading the protocol stream. Whatever it does, the query waiting behind it waits too — so no blocking calls, no database work, no HTTP. Hand anything slow to a queue or an executor and return immediately.

An exception thrown by a handler is caught and logged rather than propagated: a broken handler cannot desynchronize the connection or fail somebody's query.

## Session health and lifecycle

Three members on the session that are easy to miss, all of them about the connection rather than about queries:

| Member             | Does                                                                                                                                        |
|:-------------------|:--------------------------------------------------------------------------------------------------------------------------------------------|
| `isValid(timeout)` | Round-trips an empty query to prove the connection is alive. `timeout` is in seconds and only ever *tightens* the existing network timeout. |
| `networkTimeout`   | Read timeout in milliseconds for this connection, readable and writable at any point. `socketTimeout` sets its initial value.               |
| `abort()`          | Kills the connection outright so a pool evicts it instead of reusing it. The blunt instrument for "this connection must not be handed on".  |

`isValid` is what HikariCP calls to validate a borrowed connection; how it shares the connection with a query on another thread is in [Concurrency](concurrency.md#two-threads-on-one-session). It answers `false` for a dead connection rather than throwing; the one thing it does throw for is misuse, such as being called from inside a streaming block on its own connection.

`abort()` is the same work `Connection.abort(executor)` does, minus the exception that method deliberately throws to make a pool notice. Reach for it when a connection is known to be in a state you would rather not pass on — after an interruptible listener loop, for instance, which uses it internally on shutdown.

## Configuration reference

Every value below is parsed where it is set — in a URL, through `setProperty`, or out of a pool's property map — and one that does not parse as its type is refused there, naming the property and what it was given. Nothing falls back to a default: a misspelled `sslmode` would otherwise be a connection that verifies nothing, and a `socketTimeout` written `30s` a read that waits forever, both of them silently. A property *name* the driver does not know is a different matter and is not refused — it cannot be, since [a startup parameter](#startup-parameters) is precisely a name the driver has never heard of.

### Connection and authentication

| Property                                         | Default           | Meaning                                                                                                |
|:-------------------------------------------------|:------------------|:-------------------------------------------------------------------------------------------------------|
| `user`                                           | `postgres`        | Database username.                                                                                     |
| `password`                                       | none              | Password for that user.                                                                                |
| `serverName` (or `host`)                         | `localhost`       | Address of the database server, or [several](#several-servers) separated by commas.                    |
| `portNumber` (or `port`)                         | `5432`            | Port the server listens on — with several, of every one not stating its own.                           |
| `databaseName` (or `database`)                   | `postgres`        | Database to connect to.                                                                                |
| `targetSessionAttrs` (or `target_session_attrs`) | `ANY`             | Which kind of server to settle for among several; see [the table](#which-server-target_session_attrs). |
| `loadBalanceHosts` (or `load_balance_hosts`)     | `DISABLE`         | `RANDOM` tries the servers, and each one's addresses, in random order.                                 |
| `applicationName` (or `application_name`)        | `Octavius Driver` | Name this connection reports to the server, shown in `pg_stat_activity`.                               |

There is no property selecting an authentication method: the server names it and [the driver either answers or refuses](#authentication-is-scram-sha-256-or-a-password-inside-tls). What you can choose is whether it must be [bound to the TLS channel](#channel-binding), with `channelBinding` in the SSL table below.

### Network and limits

| Property                         | Default                                                     | Meaning                                                                                                                                                                                                                      |
|:---------------------------------|:------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `loginTimeout`                   | `DriverManager.getLoginTimeout()`, or 10 s when that is `0` | Seconds to wait for the socket connect and login, at each address tried.                                                                                                                                                     |
| `socketTimeout`                  | `0` — wait forever                                          | Seconds to wait on a socket read before failing.                                                                                                                                                                             |
| `cancelSignalTimeout`            | `10`                                                        | Seconds allowed for a [cancel request](queries.md#cancelling-a-query-in-flight), covering both its connect and its reads. It travels on a connection of its own, so it gets a budget of its own.                             |
| `maxCachedRowSize`               | `65536`                                                     | Largest row, in bytes, kept in the reusable row buffer.                                                                                                                                                                      |
| `notificationBufferCapacity`     | `256`                                                       | Capacity of the `LISTEN`/`NOTIFY` buffer; see [Listen & Notify](listen-notify.md).                                                                                                                                           |
| `noticeHandlerClassName`         | none                                                        | Fully-qualified class name of a `NoticeHandler` for server notices; see [Handling them yourself](#handling-them-yourself).                                                                                                   |
| `noticeHandler`                  | none                                                        | A `NoticeHandler` already built, used in place of `noticeHandlerClassName`. Only code can set it.                                                                                                                            |
| `initialParameterWriterCapacity` | `1024`                                                      | Starting size, in bytes, of the per-connection parameter buffer.                                                                                                                                                             |
| `maxParameterWriterCapacity`     | `65536`                                                     | Cap for that buffer; it shrinks back to the initial size after a query that exceeded it.                                                                                                                                     |
| `logParameterValues`             | `false`                                                     | Whether a traced statement carries the values bound to it rather than only their count. Effective only at `trace`, and never applies to credentials — see [Logging](logging.md#parameter-values-are-a-property-not-a-level). |

### SSL

| Property                         | Default                                | Meaning                                                                                      |
|:---------------------------------|:---------------------------------------|:---------------------------------------------------------------------------------------------|
| `sslMode` (or `sslmode`)         | `PREFER`, or `REQUIRE` if `ssl = true` | Negotiation mode; see the table below.                                                       |
| `ssl`                            | unset                                  | Shorthand: `true` raises the default to `REQUIRE`. Ignored when `sslMode` is set explicitly. |
| `sslRootCert` (or `sslrootcert`) | JVM default trust store                | Path to the root CA certificate. Optional — see the mode table below.                        |
| `sslCert` (or `sslcert`)         | none                                   | Path to the client certificate. Needs `sslKey` too, or neither is used.                      |
| `sslKey` (or `sslkey`)           | none                                   | Path to the client private key. PKCS#8 in PEM form, encrypted or not; RSA or EC.             |
| `sslPassword` (or `sslpassword`) | none                                   | Decrypts `sslKey` when that key is encrypted; ignored when it is not.                        |
| `channelBinding`                 | `PREFER`                               | How hard to insist on [channel binding](#channel-binding) for authentication.                |

The accessor is camelCase and `libpq`'s own all-lowercase spelling is accepted beside it, so a URL pasted
from PostgreSQL's documentation — `?sslmode=verify-full&sslrootcert=/etc/ca.pem` — is read as written.

| `SslMode`     | Behaviour                                                                                                                  |
|:--------------|:---------------------------------------------------------------------------------------------------------------------------|
| `DISABLE`     | No TLS request is sent at all.                                                                                             |
| `PREFER`      | Ask for TLS; fall back to a plaintext connection if the server declines. Default.                                          |
| `REQUIRE`     | Encrypt or fail — but no certificate verification.                                                                         |
| `VERIFY_CA`   | Require TLS and verify the server certificate — against `sslrootcert` when given, otherwise the JVM's default trust store. |
| `VERIFY_FULL` | Also verify that the certificate matches the hostname.                                                                     |

The handshake is restricted to TLS 1.2 and 1.3. A server that refuses TLS under `REQUIRE`, `VERIFY_CA` or `VERIFY_FULL` produces an `InitializationException(SSL_ERROR)` naming the mode you asked for, and so does a certificate that `VERIFY_CA` or `VERIFY_FULL` refuses. Note the default: unless you say otherwise, Octavius *attempts* an encrypted connection and quietly accepts plaintext if the server has no TLS — set `REQUIRE` or stronger when that fallback is not acceptable.

The mode covers more than the session's own socket. [`cancelQuery()`](queries.md#cancelling-a-query-in-flight) has to open a second connection — the protocol gives it no choice — and it carries the backend's cancel key, so it negotiates under the same settings rather than falling back to plaintext.
