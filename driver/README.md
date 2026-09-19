# Octavius Driver

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/driver)](https://central.sonatype.com/artifact/io.github.octavius-framework/driver)

A PostgreSQL 18+ driver for Kotlin.

> The lowest layer of [Octavius for PostgreSQL](../README.md), and usable entirely on its own. Everything else
> in this repository is built on it; nothing in it is built on anything else here.

**Octavius is not a traditional JDBC driver.** It implements `java.sql.Connection` purely as a way in — enough
to slot into modern connection pools like **HikariCP**. Past that point it sheds the legacy baggage: no
stateful `ResultSet`, no `CallableStatement`, no manual resource bookkeeping. It speaks PostgreSQL's Wire
Protocol v3.2 directly, with no other driver wrapped or delegated to underneath.

```kotlin
val senators: List<Senator> = session
    .createNamedQuery("SELECT id, cognomen FROM senators WHERE province_id = @province")
    .fetchObjects("province" to 7)
```

## Key Features

- **Native protocol implementation** — Wire Protocol v3.2 spoken directly, nothing wrapped underneath.
- **Virtual threads without pinning** — blocking I/O scales on Java 21 virtual threads because nothing in the
  driver is `synchronized`; it locks with `ReentrantLock` throughout. `OctaviusDispatchers.Virtual` hands you
  a dispatcher backed by them. [How it behaves under concurrency](../docs/driver/concurrency.md).
- **Parameters bound, not interpolated** — queries and DML go through the Extended Query Protocol's
  Parse/Bind/Execute cycle, in binary. Statements with nothing to bind (DDL, `SET`, `LISTEN`, `COPY`) use the
  simple protocol, which is what it is for.
- **A type system that reads your database** — the catalog is loaded from *your* schema at connect time, so a
  type you created is never an unknown OID: enums, composites, domains, ranges and table row types all come
  back as usable values without being taught to the driver. Binding one to a class of your own is a single
  `registerEnum<T>()` or `registerAutoComposite<T>()` at startup, and nested structures like
  `List<YourDataClass>` follow from there.
- **PostgreSQL's own types, not just the portable ones** — `json`/`jsonb` as Kotlinx Serialization elements,
  `uuid`, `interval`, `inet`/`cidr`/`macaddr`, `bit`/`varbit`, the geometric family, and dates and times as
  `kotlinx.datetime` values. [The full table](../docs/driver/type-system.md#basic-codecs) fits on one page.
- **Results in the shape you asked for** — `fetchRows`, `fetchObjects<T>`, `fetchField<T>`, each with
  single-row and strict variants, plus `forEach*` for streaming results too large to hold.
- **Exceptions you can act on** — a flat hierarchy keyed by SQLSTATE, each carrying the server's own error
  fields plus the SQL and parameters your application sent.
- **Asynchronous notifications** — `LISTEN` / `NOTIFY` as a Kotlin Coroutines `SharedFlow`.
- **Bulk paths that are actually fast** — native `COPY` support, and
  [`UNNEST` inserts](../docs/driver/bulk-writes.md) that beat classic JDBC batching by ~3×.
- **Large Objects as a first-class API** — `lo` support without unwrapping to a vendor interface.
- **TLS with the property names you already know** — the full `sslmode` ladder from `prefer` to `verify-full`,
  client certificates and root CA included, with the handshake restricted to TLS 1.2 and 1.3.
- **Connection pool ready** — designed around HikariCP, with the Kotlin session API layered on top.

## Requirements

- **Java 21+**
- **Kotlin 2.4+** — every ergonomic entry point is `inline` with a `reified` parameter — `fetchObjects<T>`,
  `fetchField<T>`, `row.get<T>()`, `registerEnum<T>()` — so they are callable from Kotlin and not from Java.
  Columns also come back as `kotlin.time.Instant` and `kotlin.uuid.Uuid`.
- **PostgreSQL 18+** — Wire Protocol v3.2 exclusively, introduced in PostgreSQL 18. Older servers expect v3.0
  and the connection fails during the handshake.

[The repository README](../README.md#requirements) has the reasoning behind each of these, and the one further
requirement that belongs to `driver-spring-integration` rather than to the driver.

## Project status

1.0.0 is released, and every module in this repository carries that version. What the number does and does not
promise, and how each push is tested, is in the [repository README](../README.md#project-status).

The driver has not seen long production use: the figures under [Performance](#performance) come from
benchmarks, not from a year of traffic.

## Quick Start

```kotlin
dependencies {
    implementation("io.github.octavius-framework:driver:2.1.0")
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Or, for Spring Boot - brings the driver in transitively
    // implementation("io.github.octavius-framework:driver-spring-integration:2.1.0")
}
```

```kotlin
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

val config = HikariConfig().apply {
    jdbcUrl = "jdbc:octavius://localhost:5432/res_publica"
    username = "consul"
    password = "senatus_populusque"
}
val dataSource = HikariDataSource(config)

// 1. Pull a session through HikariCP via the custom jdbc:octavius protocol
val session = dataSource.getOctaviusSession()

// 2. Named parameters, and exactly one row expected (or an exception)
val row = session.createNamedQuery("SELECT id, cognomen FROM senators WHERE id = @id")
    .fetchRowStrict("id" to 1)

val id: Int = row.get("id")
val cognomen: String = row.get("cognomen")

// 3. Or skip the row entirely and map straight onto your own class
data class Senator(val id: Int, val cognomen: String)

val active: List<Senator> = session
    .createNativeQuery("SELECT id, cognomen FROM senators WHERE active = $1")
    .fetchObjects(true)

// 4. Group work into a transaction - committed on success, rolled back on any throw
session.transaction.required {
    createNativeQuery("INSERT INTO senators (cognomen) VALUES ($1)").update("Cato")
    createNativeQuery("INSERT INTO senate_logs (event) VALUES ($1)").update("New senator inducted")
}

session.close() // Safely returns the connection to the pool
```

## Performance

Measured against `pgjdbc` on the same machine, same JVM, same work
([full numbers and caveats](../docs/driver/performance.md)):

- **Object mapping ties** — 0.227 ± 0.008 against 0.223 ± 0.009 ops/ms, a dead heat on the path most
  applications live on.
- **`UNNEST` bulk inserts are 3.4× faster than classic JDBC batching**, and tie with pgjdbc's
  `reWriteBatchedInserts` optimization — while remaining usable for `UPDATE` and `DELETE`, where that
  optimization does not apply.
- **Array decoding costs ~28%** for going through the general conversion machinery — the same machinery that
  makes a `List<Senator>` of composites work.
- **Reflective mapping allocates more than a hand-written converter does** — ~2.5× on the two-field row
  benchmarked, in both directions. Worth replacing on your hottest path, and nowhere else.

## Documentation

**[API Reference](https://octavius-framework.github.io/octavius-postgresql/)** — generated KDoc for every
declaration in every published module, rebuilt on each push to `master`. Reach for it when you need a
signature, a property, or the values of an enum.

The guides cover what a signature cannot show — how the pieces behave together.
[The documentation index](../docs/driver/README.md) lists every one of them section by section; below is the
shortlist.

**Start here**
- [Quickstart](../docs/driver/quickstart.md) — from an empty project to a first query.
- [**Octavius vs Legacy JDBC**](../docs/driver/octavius-vs-jdbc.md) — what was dropped, and why.
- [Session Initialization and Configuration](../docs/driver/initialization.md) — every connection option,
  pooling, and what survives a return to the pool.

**Everyday work**
- [Executing Queries](../docs/driver/queries.md) — parameters, the `fetch*` family, streaming.
- [Transaction Management](../docs/driver/transactions.md) — block API, manual control, savepoints, isolation.
- [Type System & Mapping](../docs/driver/type-system.md) — how columns become Kotlin types, and how to extend
  that.
- [Arrays, Ranges and JSON](../docs/driver/arrays-ranges-json.md) — reading and writing the three that have
  corners worth knowing.
- [Composites & Reflection](../docs/driver/composites-reflection.md) — data classes in and out, and what to
  write when reflection is not the mapping you want.
- [Bulk Writes](../docs/driver/bulk-writes.md) — thousands of rows in one statement, and what replaced
  `addBatch()`.
- [Error Handling & Exceptions](../docs/driver/exceptions.md) — the hierarchy, and catching at the right
  altitude.
- [Logging](../docs/driver/logging.md) — logger names, what each level says, and what never reaches the log.
- [Spring Integration](../docs/driver/spring-integration.md) — `OctaviusTemplate` and autoconfiguration.

**Specialized**
- [Concurrency and Virtual Threads](../docs/driver/concurrency.md) — what one connection serializes, and where
  the real limit is.
- [Functions and Procedures](../docs/driver/functions-procedures.md) — no `CallableStatement` required.
- [COPY Protocol](../docs/driver/copy.md) — bulk import and export.
- [Listen & Notify](../docs/driver/listen-notify.md) — asynchronous events as a flow.
- [Large Objects](../docs/driver/large-objects.md) — beyond what `bytea` can hold.
- [Performance](../docs/driver/performance.md) — JMH benchmarks against `pgjdbc`.

## Architecture

- **IO, SSL & Auth** — socket handling (`PgStream`), buffering, TLS negotiation, SCRAM-SHA-256 with channel
  binding.
- **Message** — parsing and building Wire Protocol v3.2 packets.
- **Query, Execution & Parser** — the operational core: Extended Query Protocol with named-parameter support.
- **Codec, Converter & Registry** — the two-layer type system, from raw binary through to your own classes.
- **Type & Container** — the PostgreSQL value model the layer above hands back: `Row`, `PgArray`,
  `PgComposite`, `PgRange`, `PgMultirange`.
- **Session & Transaction** — `OctaviusSession`, transaction blocks and savepoints.
- **COPY & LO** — bulk import and export, and Large Objects.
- **Notification & Notice** — `LISTEN`/`NOTIFY` as a flow, and server notices routed to a `NoticeHandler`.
- **Exception** — the SQLSTATE-keyed hierarchy, built from the server's own error fields.
- **JDBC** — the compatibility layer that lets pools like HikariCP manage the connection.

What sits alongside it in this repository — the client, the annotations, the Spring integration — is listed in
the [repository README](../README.md#what-is-here).

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
