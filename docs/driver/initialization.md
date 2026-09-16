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

An IPv6 address goes in brackets, the way libpq writes it — `jdbc:octavius://[::1]:5432/res_publica`. They are what tells the address's own colons from the one before the port, so they are required even with no port following: an unbracketed `::1` in a URL reads as the host `:` on port 1. They belong to the URL rather than to the address, though, so `serverName` comes back as a bare `::1` — and that is the form to set directly on `OctaviusProperties` or `OctaviusDataSource`, where there is no port to separate it from.

### 3. `OctaviusDataSource`

`OctaviusDataSource` bypasses `DriverManager` and takes typed values directly:

```kotlin
import io.github.octaviusframework.driver.jdbc.OctaviusDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession

val dataSource = OctaviusDataSource().apply {
    serverName = "localhost"
    portNumber = 5432
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"
}

val session = dataSource.getOctaviusSession()
```

Every field of `OctaviusProperties` has an accessor here — address, credentials, the SSL group and every tuning knob in the [configuration reference](#configuration-reference) — so nothing is reachable only through a URL:

```kotlin
val dataSource = OctaviusDataSource().apply {
    serverName = "localhost"
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"

    socketTimeout = 30
    maxCachedRowSize = 8192
    noticeHandler = "com.example.MyNoticeHandler"
    applicationName = "LegioXIII-App"

    // Anything without an accessor of its own, startup parameters included
    setProperty("search_path", "castra, public")
}
```

That matters most behind a pool, because `dataSourceClassName` configuration goes through JavaBean setters and can only set what has one — see [below](#via-octaviusdatasource).

The `url` property remains the other route in: it parses a full URL and merges it into the same underlying `OctaviusProperties`, so the two can be combined.

```kotlin
val dataSource = OctaviusDataSource().apply {
    url = "jdbc:octavius://localhost:5432/res_publica?maxCachedRowSize=8192"
    user = "consul"
    password = "senatus_populusque"
}
```

> [!NOTE]
> Reading `dataSource.url` back renders the current configuration as a URL, but **never the password** — that string is the one most likely to reach a log. The password stays readable through `dataSource.password`, and `OctaviusProperties.copy()` is the lossless way to duplicate a full configuration.

## Adding HikariCP Connection Pooling

Octavius is fully compatible with connection pools like [HikariCP](https://github.com/brettwooldridge/HikariCP). Although it is a Kotlin-first driver, it implements standard `java.sql.Connection` precisely so it can slot into one.

### Via JDBC URL

```kotlin
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession

val config = HikariConfig().apply {
    jdbcUrl = "jdbc:octavius://localhost:5432/res_publica"
    username = "consul"
    password = "senatus_populusque"
    maximumPoolSize = 10
}
val pool = HikariDataSource(config)

val session = pool.getOctaviusSession() // borrows a session
```

> [!NOTE]
> Constructing the pool opens a connection to check the configuration, so a driver failure surfaces there as HikariCP's own `PoolInitializationException` — you called their constructor, you get their exception. The driver's exception is its cause, one `findOctaviusCause()` away; see [Digging it out yourself](exceptions.md#digging-it-out-yourself). Borrowing (`getOctaviusSession()`) needs none of that: a refusal there already arrives as `InitializationException`.

### Via `OctaviusDataSource`

```kotlin
val config = HikariConfig().apply {
    dataSourceClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDataSource"

    addDataSourceProperty("serverName", "localhost")
    addDataSourceProperty("portNumber", "5432")
    addDataSourceProperty("databaseName", "res_publica")
    addDataSourceProperty("user", "consul")
    addDataSourceProperty("password", "senatus_populusque")

    // Tuning knobs work here too - each one is a bean property
    addDataSourceProperty("socketTimeout", "30")
    addDataSourceProperty("maxCachedRowSize", "8192")

    addDataSourceProperty("applicationName", "curia-api")  // the name pg_stat_activity will show

    maximumPoolSize = 10
}
val pool = HikariDataSource(config)

val session = pool.getOctaviusSession()
```

`addDataSourceProperty` sets a JavaBean property by reflection, so the name has to match an accessor on `OctaviusDataSource` exactly — a name it does not know is an error at pool startup rather than a setting quietly ignored. Every property in the [configuration reference](#configuration-reference) has one.

Two things do **not** go through it:

* **`sslMode`**, because its accessor is typed as the `SslMode` enum. HikariCP converts a string value only for primitives, `Boolean` and `String`; anything else it passes through untouched, and a `String` arriving at a setter expecting `SslMode` fails the pool with *"argument type mismatch"*. Passing the enum itself — `addDataSourceProperty("sslMode", SslMode.REQUIRE)` — works, but a properties file or Spring's `data-source-properties` has only strings to offer.
* **Startup parameters**, which are not driver settings at all and so have no accessor by design. `application_name` is the exception: it has [a property of its own](#startup-parameters), typed as a `String`, so it goes through like any other.

Both fit on the `url` property, which is itself a bean property and takes a whole URL:

```kotlin
addDataSourceProperty("url", "jdbc:octavius://localhost:5432/res_publica?sslmode=require&search_path=castra")
```

Everything else — the timeouts, the buffer sizes, `ssl`, `noticeHandler`, the certificate paths — is a plain `Int`, `Boolean` or `String` accessor and takes a string value as it stands.

### Via a configured instance

The reflection above is only one of the two ways Hikari accepts a `DataSource`. Hand it an instance you configured yourself and it uses that object directly:

```kotlin
import io.github.octaviusframework.driver.ssl.SslMode

val octavius = OctaviusDataSource().apply {
    serverName = "localhost"
    databaseName = "res_publica"
    user = "consul"
    password = "senatus_populusque"

    sslMode = SslMode.REQUIRE   // an enum, not a string that has to survive a conversion
    socketTimeout = 30
    applicationName = "curia-api"
}

val pool = HikariDataSource(HikariConfig().apply {
    dataSource = octavius
    maximumPoolSize = 10
})
```

Nothing is set by name and nothing is coerced, so every accessor is usable at its real type and the caveats above do not apply — `sslMode` included. The compiler checks the configuration, which the string forms cannot. In Kotlin this is the route worth reaching for first; `dataSourceClassName` earns its place where the configuration has to come from a properties file or from Spring's `spring.datasource.hikari.data-source-properties`.

Closing a session obtained from a pool returns its connection to the pool rather than shutting it down.

Leave the pool's own auto-commit setting at its default (`true`). Configuring a pool with `auto-commit=false` makes every connection in it sit `idle in transaction` while waiting to be borrowed — see [Transactions](transactions.md#manual-control) for why, and what it collides with.

## What survives a return to the pool

A pooled connection outlives the session you borrowed it through, so whatever is left set on it becomes the next borrower's starting state. Some of that is cleaned up for you:

| Connection state                                       | Undone when the session closes?                                              |
|:-------------------------------------------------------|:-----------------------------------------------------------------------------|
| `autoCommit`, `readOnly`, `transactionIsolationLevel`  | Yes — HikariCP tracks these through its own proxy and restores its defaults. |
| A `COPY` the caller never finished                     | Yes — by evicting the connection instead of handing it back.                 |
| `LISTEN` registrations made via `notifications.listen` | Yes — the session issues `UNLISTEN *` if it subscribed to anything.          |
| A transaction left open by a hand-written `BEGIN`      | Yes — rolled back, since the driver cannot know what that work was for.      |
| **Anything else you set by running the SQL yourself**  | **No.**                                                                      |

That last row is the one to internalize, because it is not a gap anyone can close: neither the pool nor the driver parses the statements you send, so neither can know that a `SET search_path`, a `SET statement_timeout`, a `SET SESSION CHARACTERISTICS AS TRANSACTION ...`, a hand-written `LISTEN`, or a temporary table ever happened. All of it stays on the connection until the connection itself dies, and the next borrower inherits it.

Two habits keep you out of that:

* **Prefer the typed API wherever one exists** — `session.transactionIsolationLevel` over `SET SESSION CHARACTERISTICS`, `session.notifications.listen` over a raw `LISTEN`. Those are exactly the paths that are tracked and undone.
* **Put per-connection defaults in [startup parameters](#startup-parameters)** rather than setting them after connecting. A `search_path` supplied at connect time is part of every connection's identity, so there is nothing to leak and nothing to restore.

Queries themselves leave nothing behind: the driver executes through unnamed statements and portals, so nothing accumulates server-side from ordinary traffic.

## What happens when a session opens

The sequence is worth knowing, because two of its steps are where connections fail and one is why the *first* connection is slower than the rest:

1. **The socket is opened**, with `loginTimeout` as its connect timeout.
2. **SSL is negotiated** — unless `sslmode=disable`, the driver asks the server for TLS before anything else is sent. See the defaults below: by default it *tries*.
3. **The startup message goes out**, carrying `user`, `database`, `client_encoding=UTF8` and every [additional property](#startup-parameters) you set.
4. **Authentication runs** with the password supplied — [SCRAM-SHA-256, or a password inside TLS](#authentication-is-scram-sha-256-or-a-password-inside-tls).
5. **Timeouts are applied** — `socketTimeout` becomes the socket read timeout, `maxCachedRowSize` the row buffer cap.
6. **The server version is checked.** Anything below PostgreSQL 18 gets the connection closed and an `InitializationException(UNSUPPORTED_SERVER_VERSION)`, naming the version received.
7. **The type catalog is loaded**, once per database — see below.

> [!IMPORTANT]
> **PostgreSQL 18 or newer is required.** Octavius speaks Wire Protocol v3.2 exclusively. Against an older server the failure shows up in step 6 as an `InitializationException`, or earlier at the protocol level during the handshake.

### Authentication is SCRAM-SHA-256, or a password inside TLS

Step 4 has three happy endings: the server asks for SCRAM-SHA-256, the server asks for a cleartext password on a connection that is already encrypted, or the server asks for nothing at all — `trust` in `pg_hba.conf`, or a client certificate already accepted during the TLS handshake.

| The server asks for                | Result                                                     |
|:-----------------------------------|:-----------------------------------------------------------|
| SCRAM-SHA-256-PLUS                 | Connected, bound to the TLS channel.                       |
| SCRAM-SHA-256                      | Connected.                                                 |
| Nothing — `trust`, `cert`          | Connected.                                                 |
| A cleartext password, over TLS     | Connected; the password goes out as it is, inside TLS.     |
| A cleartext password, in plaintext | `InitializationException(UNSUPPORTED_PASSWORD_ENCRYPTION)` |
| MD5                                | `InitializationException(UNSUPPORTED_PASSWORD_ENCRYPTION)` |
| GSSAPI, SSPI, anything else        | `InitializationException(UNSUPPORTED_MECHANISM)`           |

**The cleartext row is the one worth reading twice.** The server asks for a password in the clear when `pg_hba.conf` names `password`, `ldap`, `pam` or `radius` — the last three because the server has to hold the password itself to check it against something that is not PostgreSQL. Nothing on the client side makes that safer: the password travels as it was typed, and the only thing between it and the wire is the encryption underneath. So the driver insists on that encryption rather than trusting the deployment to have arranged it — raise `sslmode` to `require` or above and the exchange proceeds; leave it where the connection ends up in plaintext and the password is never sent. `channelBinding=require` refuses the exchange outright, before the password leaves the JVM, because no cleartext login can be bound to the channel.

What decides which of those the server asks for is how the role's password is stored, not the `pg_hba.conf` line alone — a role whose password was set while `password_encryption` was `md5` still carries an MD5 verifier, and that is what the server offers whatever the line says. `ALTER ROLE … PASSWORD …` re-hashes it under the current setting, which on PostgreSQL 18 is SCRAM by default.

### Channel binding

Under `sslmode=require` the connection is encrypted and *nothing checks who is on the other end of it* — the driver accepts whatever certificate the server presents. Channel binding is what closes that gap. The client proof is computed over a hash of the certificate actually on the wire, so an intermediary that terminated TLS with a certificate of its own hands the real server a proof that does not verify: it cannot relay an exchange it is not itself a party to.

How hard the driver insists is the `channelBinding` property:

| Value     | Behaviour                                                                |
|:----------|:-------------------------------------------------------------------------|
| `prefer`  | Bind when the connection is encrypted and the server offers it. Default. |
| `require` | Refuse to log in at all unless the exchange was bound.                   |
| `disable` | Never offer it; the exchange declares no support for binding.            |

PostgreSQL offers `SCRAM-SHA-256-PLUS` on every encrypted connection, so under the default *every* TLS connection to PostgreSQL ends up bound — there is nothing to switch on. What `require` buys is the failure: without TLS, or against a server that does not offer binding, you get an `InitializationException(UNSUPPORTED_MECHANISM)` rather than a quietly unbound login. It also rejects a connection the server waved through without asking, since `trust` is not a bound exchange either.

Binding hashes the certificate; it does not judge it. That still belongs to [`verify-full`](#ssl), and the two answer different questions worth answering together: binding proves the exchange reached the holder of *that* certificate, `verify-full` proves that certificate is the one you meant to reach.

> [!NOTE]
> When the connection is encrypted but the server offers no binding mechanism, the driver says as much in the handshake instead of silently taking the weaker exchange. PostgreSQL always offers binding over TLS, so it reads that as the contradiction it is and answers `SCRAM channel binding negotiation error`. Meeting that error means something between you and the server rewrote the mechanism list.

### The first connection pays for the type catalog

Step 7 reads the server's type catalog into a `TypeCatalog`, held in `GlobalCatalogStore` per database and keyed by **host, port and database name** — deliberately not by the full URL, so credentials, SSL settings and timeouts do not fragment the cache. Every later connection to that same database, from any pool in the JVM, reuses the loaded catalog and skips the work.

In practice: the first connection a pool opens is measurably slower than its siblings, and pre-warming one connection at startup moves that cost out of your first request. If your application connects to thousands of distinct databases over its lifetime, `GlobalCatalogStore.removeCatalog(url)` releases a catalog you are done with. The details of what gets loaded, and how to refresh it after a migration, are in [Type System](type-system.md#the-catalog-load).

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

**`application_name` is the one with a property of its own.** Nearly every application sets it, and a plain `String` property is what lets a pool or a properties file reach it without going through a URL. Either spelling fills the same field — `applicationName` or PostgreSQL's own `application_name` — and the property wins over an `application_name` put into `additionalProperties` by hand.

```kotlin
val properties = OctaviusProperties().apply {
    applicationName = "LegioXIII-App"  // shows up in pg_stat_activity
}
```

Say nothing and the connection reports **`Octavius Driver`**, so a `pg_stat_activity` full of driver connections says at least which driver opened them. Naming your own application is still worth the one line: the default tells a DBA what the connection is, not who it is for. An `application_name` you had already put into `additionalProperties` is left as it is — the driver's name only fills a gap.

**Character encoding is the one exception you cannot override**: the driver always declares UTF-8 to the server, and there is no plan to support anything else, so it is not exposed as a setting.

## Notices from the server

PostgreSQL sends notices alongside ordinary traffic — a `RAISE NOTICE` from a routine, a "table does not exist, skipping" from a `DROP ... IF EXISTS`, a deprecation warning. They are not errors and nothing is interrupted by one.

JDBC's answer is `SQLWarning` objects accumulating on the connection until someone calls `clearWarnings()`, which is [a leak waiting to happen](octavius-vs-jdbc.md#what-answers-quietly-instead-of-throwing). Octavius pushes instead: every notice is logged as it arrives, and handed to a `NoticeHandler` if you configured one. Nothing accumulates and nothing needs draining.

**They are always logged**, handler or no handler, under a logger name of their own so they can be turned up or down without touching anything else the driver writes — see [Server notices](logging.md#server-notices) for the name, the severity mapping and how to configure it. The rest of this section is about handling them in code.

### Handling them yourself

Implement `NoticeHandler` and name the class in the [`noticeHandler` property](#network-and-limits):

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
properties.noticeHandler = "com.example.AuditNoticeHandler"
// or on the URL: ?noticeHandler=com.example.AuditNoticeHandler
```

A Kotlin `object` is reused as a singleton across every connection; a class is instantiated per connection through its no-arg constructor. `PgNotice` carries every field a notice can hold, under the same names `ServerErrorMessage` uses: `severity`, `code` and `message` always arrive, `file`, `line` and `routine` come with every notice too, and `detail`, `hint`, `schema`, `table`, `column`, `datatype` and `constraint` are what a `RAISE ... USING` fills in. The comparison above is safe because `severity` is read from the non-localized field — the translated severity a non-English `lc_messages` produces is kept apart, as `localizedSeverity`.

It also carries `processId`, the same backend process id the log line is prefixed with — which is what a singleton handler needs, since it sees the notices of every connection in the pool arriving on one method. The server does not send it as part of the notice; the driver takes it from the connection's startup handshake, so a notice raised before that handshake finished reports `-1`. Nothing reaches that window at the default verbosity — connecting with `client_min_messages=debug5` is what puts a message there, the backend's own catalog-reading transaction — but a handler that buckets by process id should expect the value to exist. It is not the same number as `PgNotification.processId`, which names the *foreign* backend that ran the `NOTIFY`.

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

`isValid` is what HikariCP calls to validate a borrowed connection, and it takes the connection lock like any other exchange — so a validation probe never shortens the deadline of a query running on another thread. It answers `false` for a dead connection rather than throwing; the one thing it does throw for is misuse, such as being called from inside a streaming block on its own connection.

`abort()` is the same work `Connection.abort(executor)` does, minus the exception that method deliberately throws to make a pool notice. Reach for it when a connection is known to be in a state you would rather not pass on — after an interruptible listener loop, for instance, which uses it internally on shutdown.

## Configuration reference

Every value below is parsed where it is set — in a URL, through `setProperty`, or out of a pool's property map — and one that does not parse as its type is refused there, naming the property and what it was given. Nothing falls back to a default: a misspelled `sslmode` would otherwise be a connection that verifies nothing, and a `socketTimeout` written `30s` a read that waits forever, both of them silently. A property *name* the driver does not know is a different matter and is not refused — it cannot be, since [a startup parameter](#startup-parameters) is precisely a name the driver has never heard of.

### Connection and authentication

| Property                                  | Default           | Meaning                                                                  |
|:------------------------------------------|:------------------|:-------------------------------------------------------------------------|
| `user`                                    | `postgres`        | Database username.                                                       |
| `password`                                | none              | Password for that user.                                                  |
| `serverName` (or `host`)                  | `localhost`       | Address of the database server.                                          |
| `portNumber` (or `port`)                  | `5432`            | Port the server listens on.                                              |
| `databaseName` (or `database`)            | `postgres`        | Database to connect to.                                                  |
| `applicationName` (or `application_name`) | `Octavius Driver` | Name this connection reports to the server, shown in `pg_stat_activity`. |

There is no property selecting an authentication method: the server names it and [the driver either answers or refuses](#authentication-is-scram-sha-256-or-a-password-inside-tls). What you can choose is whether it must be [bound to the TLS channel](#channel-binding), with `channelBinding` in the SSL table below.

### Network and limits

| Property                         | Default                                                     | Meaning                                                                                                                                                                                                                      |
|:---------------------------------|:------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `loginTimeout`                   | `DriverManager.getLoginTimeout()`, or 10 s when that is `0` | Seconds to wait for the socket connect and login.                                                                                                                                                                            |
| `socketTimeout`                  | `0` — wait forever                                          | Seconds to wait on a socket read before failing.                                                                                                                                                                             |
| `cancelSignalTimeout`            | `10`                                                        | Seconds allowed for a [cancel request](queries.md#cancelling-a-query-in-flight), covering both its connect and its reads. It travels on a connection of its own, so it gets a budget of its own.                             |
| `maxCachedRowSize`               | `65536`                                                     | Largest row, in bytes, kept in the reusable row buffer.                                                                                                                                                                      |
| `notificationBufferCapacity`     | `256`                                                       | Capacity of the `LISTEN`/`NOTIFY` buffer; see [Listen & Notify](listen-notify.md).                                                                                                                                           |
| `noticeHandler`                  | none                                                        | Fully-qualified class name of a `NoticeHandler` for server notices. A Kotlin `object` is reused as a singleton across connections; a class is instantiated per connection through its no-arg constructor.                    |
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
from PostgreSQL's documentation — `?sslmode=verify-full&sslrootcert=/etc/ca.pem` — is read as written. A pool
setting bean properties by name is the one place the distinction bites: HikariCP's `addDataSourceProperty`
resolves the name to a setter, so it wants `sslMode`.

| `SslMode`     | Behaviour                                                                                                                  |
|:--------------|:---------------------------------------------------------------------------------------------------------------------------|
| `DISABLE`     | No TLS request is sent at all.                                                                                             |
| `PREFER`      | Ask for TLS; fall back to a plaintext connection if the server declines. Default.                                          |
| `REQUIRE`     | Encrypt or fail — but no certificate verification.                                                                         |
| `VERIFY_CA`   | Require TLS and verify the server certificate — against `sslrootcert` when given, otherwise the JVM's default trust store. |
| `VERIFY_FULL` | Also verify that the certificate matches the hostname.                                                                     |

The handshake is restricted to TLS 1.2 and 1.3. A server that refuses TLS under `REQUIRE`, `VERIFY_CA` or `VERIFY_FULL` produces an `InitializationException(SSL_ERROR)` naming the mode you asked for. Note the default: unless you say otherwise, Octavius *attempts* an encrypted connection and quietly accepts plaintext if the server has no TLS — set `REQUIRE` or stronger when that fallback is not acceptable.

The mode covers more than the session's own socket. [`cancelQuery()`](queries.md#cancelling-a-query-in-flight) has to open a second connection — the protocol gives it no choice — and it carries the backend's cancel key, so it negotiates under the same settings rather than falling back to plaintext.
