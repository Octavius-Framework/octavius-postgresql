# Octavius for PostgreSQL

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/driver)](https://central.sonatype.com/namespace/io.github.octavius-framework)
[![Build and Test](https://github.com/Octavius-Framework/octavius-postgresql/actions/workflows/tests.yml/badge.svg)](https://github.com/Octavius-Framework/octavius-postgresql/actions/workflows/tests.yml)
[![API Reference](https://img.shields.io/badge/docs-API%20reference-blue)](https://octavius-framework.github.io/octavius-postgresql/)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)

*Not an ORM. A **ROME** — a Relational-Object Mapping Engine. Because all queries lead to ROME.*

Everything Octavius has to say about talking to PostgreSQL from Kotlin: a driver that speaks the wire protocol
directly, a data access layer built on it, and a migrator that keeps the schema up to date.

They are separate artifacts on purpose. The driver is complete on its own — a connection pool and the driver
are a working stack, and plenty of applications need nothing else. The client is for the layer above:
somewhere to put a query that does not want a session threaded through its signature. Migrations answers a
different question again, and takes the driver alone — never the client.

## Philosophy

*Augustus kept every form of the Republic and quietly took the power out of them. Octavius keeps every form of
JDBC — a `Connection`, a `DataSource`, a pool that recognises both — and quietly does none of what JDBC does
underneath.*

| Principle                        | What it means here                                                                                                         |
|:---------------------------------|:---------------------------------------------------------------------------------------------------------------------------|
| **Query is Imperator**           | Your SQL dictates the shape of the result. Nothing rewrites it, reorders it, or issues a query you did not write.          |
| **Object is a Vessel**           | A `data class` is a typed container for what came back — no proxies, no lazy loading, nothing dirty to check.              |
| **No Legate Speaks for It**      | The driver talks Wire Protocol v3.2 itself. Nothing is wrapped, and no other driver is delegated to underneath.            |
| **Each Province Governs Itself** | Every artifact is its own coordinate and its own decision, and dependencies run one way. The driver alone is a working stack. |
| **One Standard, No Fallback**    | v3.2 or nothing: PostgreSQL 17 fails the handshake rather than half-working, so there are no compatibility shims to carry. |

[Design Philosophy](DESIGN_PHILOSOPHY.md) is the argument behind them — what was decided, what was rejected,
and where a reasonable person would have chosen otherwise.

## What is here

| Artifact                                       | What it is                                                                                                                                                                                                               |
|:-----------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`driver`** — *the road*                      | The core. Wire Protocol v3.2 spoken directly, a type system read from your catalog, `COPY`, `LISTEN`/`NOTIFY`, Large Objects, TLS. [README](driver/README.md)                                                            |
| **`client`** — *the praetor*                   | Session scoping, thread-bound transactions, query builders, transaction plans, `dynamic_dto`. [README](client/README.md)                                                                                                 |
| **`client-scanner`** — *the census*            | Finds the annotated classes in your packages and registers them, so thirty types are named once instead of thirty times. [README](client-scanner/README.md)                                                              |
| **`migrations`** — *the surveyor*              | A migrator on the driver: `V`/`R` naming, `.sql` files and Kotlin classes, checksums, an advisory lock, and a history table it keeps itself. [README](migrations/README.md)                                              |
| **`pg-model`** — *the codex*                   | Multiplatform: the annotations Octavius reads off your own classes, a `BigDecimal` `commonMain` can name, and the serializers that keep it and PostgreSQL's dates intact through JSON. [README](pg-model/README.md)      |
| **`driver-spring-integration`** — *the treaty* | `OctaviusTemplate`, exception translation, Spring Boot autoconfiguration. [README](driver-spring-integration/README.md)                                                                                                  |

The road carries the query, the praetor decides which court hears it, the census enrols the citizens so that
none has to present itself by name, the surveyor keeps the boundary stones and the record of who moved them,
the codex is the text every province reads, and the treaty sets the terms a foreign power works under.

Not published: `hikari-integration-tests` (integration tests against a real pool), `benchmarks` (JMH against
`pgjdbc`), and `examples/spring-app` (a runnable sample, its own build pulling the driver in through
`includeBuild`).

## Coordinates

All six live under `io.github.octavius-framework` and are released together on the same version:

```kotlin
implementation("io.github.octavius-framework:driver:1.0.0")
implementation("io.github.octavius-framework:client:1.0.0")
implementation("io.github.octavius-framework:client-scanner:1.0.0")
implementation("io.github.octavius-framework:migrations:1.0.0")
implementation("io.github.octavius-framework:driver-spring-integration:1.0.0")
```

Take the ones you want and no more — each brings what it sits on. `client` brings the driver,
`client-scanner` brings the client, `migrations` and `driver-spring-integration` bring the driver, and
`pg-model` arrives with any of them, so it is rarely named by hand.

## Which one do I want

**The driver alone** where something else already decides how connections and transactions are handled — a
Spring application, or code that is happy to open a session and use it. You write
`dataSource.getOctaviusSession()` and everything below that line is the driver's.

**The client as well** where that decision is yours to make. It answers one question the driver deliberately
leaves open — which session does this operation run on — so that a repository function can open a scope
without knowing whether it is already inside a transaction, and be right either way. The query builders and
`dynamic_dto` come with it.

**Migrations** where keeping this database's schema up to date is this application's job rather than
somebody else's. It sits beside the client rather than under it, needing only the driver, so it goes with the
client, without it, or not at all. `OctaviusMigrator(dataSource).migrate()` at startup is the whole of it.

Adding the client later costs nothing: it wraps no query and renames no method, so driver code keeps working
unchanged next to it.

## Requirements

- **Java 21+**
- **Kotlin 2.4+** — Octavius is a Kotlin library, not a JVM one. Every ergonomic entry point is `inline` with a
  `reified` parameter, and a reified function can only be inlined into Kotlin, never called. The non-reified
  layer underneath is reachable from Java, but it means hand-building a `KType` for every column you read:
  possible, not usable.
- **PostgreSQL 18+** — the driver speaks **Wire Protocol v3.2** exclusively, introduced in PostgreSQL 18. Older
  servers expect v3.0 and the connection fails during the handshake.
- **Spring Boot 4.x** — for `driver-spring-integration` only. The core driver has no Spring dependency at all.

## Project status

Written by one person. Every push runs the suite against a real PostgreSQL 18, alongside a job that generates
certificates and exercises the TLS modes end to end, and a third that points the driver at PostgreSQL 17 to
prove the handshake refuses it rather than half-working.

1.0.0 says the shape is right — the pieces are the ones worth having and each sits where it should — and not
that a signature will never move again. Every module carries the same version and is released together, so the
number says when something shipped rather than how mature it is. None of it has seen long production use.

Every change is recorded in the [CHANGELOG](CHANGELOG.md), grouped by module under each version — one file,
because one version covers all of them and a release where only half the repository moved reads oddly split
across two.

## Documentation

**[API Reference](https://octavius-framework.github.io/octavius-postgresql/)** — generated KDoc for every
declaration, rebuilt on each push to `master`. Reach for it when you need a signature, a property, or the
values of an enum.

The guides cover what a signature cannot show — how the pieces behave together. There are three sets, because
the driver stands on its own and the client and migrations are each optional beside it:
[the documentation index](docs/README.md) points at all three, and the driver's
[Quickstart](docs/driver/quickstart.md) is where to start from an empty project.

## Relation to octavius-database

[octavius-database](https://github.com/Octavius-Framework/octavius-database) is the previous generation and is
superseded by what is here. Much of it existed to work around pgjdbc — text-protocol composites, enums the
library had to be taught, a stateful `ResultSet`, no named parameters — and the driver answers all of that
natively. What was left over is the client, which is deliberately a much smaller thing than a port would have
been.

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
