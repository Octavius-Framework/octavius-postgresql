# Octavius Client

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/client)](https://central.sonatype.com/artifact/io.github.octavius-framework/client)

What the [driver](../driver/README.md) leaves to the caller, and nothing it already does.

> Part of [Octavius for PostgreSQL](../README.md), released with it and on the same version.

```kotlin
val db = OctaviusClient.fromDataSource(hikariDataSource)

val senators = db.select("id", "cognomen", "province_id")
    .from("senate")
    .where(filter.sql)            // null or empty leaves out the WHERE entirely
    .orderBy("cognomen")
    .page(page = 0, size = 20)
    .fetchObjects<Senator>(filter.params)
```

## What it adds

**A session, without a session in the signature.** The driver is session-per-connection; an application is
not. `OctaviusClient` answers the one question that leaves open — which session does this operation run on —
so a repository function can open a scope without knowing whether it is already inside a transaction, and be
right either way. Inside `db.transaction { }` a nested call joins, commits and rolls back with it; outside
one it borrows a session and gives it straight back.

**Query builders.** Every clause takes SQL and passes it through — `from("legions l JOIN provinces p ON …")` is
written out because that is the join. What the builder does is the mechanical part: the keywords, their order,
the column list paired with its own placeholders, and above all the clauses that disappear when they have
nothing to say. `where(null)` leaves out the `WHERE`, which is what makes a filter assembled at runtime
bearable to write. `QueryFragment` keeps such a filter and the parameters it names together.

**A converter, for one query.** `registerResultConverter` on a builder reaches the registries the driver gives
every query — consulted ahead of the session's, thrown away with the query. A mapping that one report needs
does not have to be registered against the whole database, and reaching it does not mean dropping out of the
builders. It is also how `dynamic_dto` reads a payload under a different `Json` for the length of one query.

**Transaction plans.** For when the sequence itself is data — built by the layer that knows what has to happen,
run by another. A step's parameters can point at what an earlier step produced, two plans merge into one
transaction, and the whole plan is checked before any of it runs rather than partway through.

**`dynamic_dto`.** One column holding whichever of several unrelated shapes a row happens to carry, which is
the case a `COMPOSITE` cannot cover: a composite fixes the shape at schema level, this fixes it per row. Bind a
class to a name once and the column reads back as that class — or as whatever supertype you asked for, where
the rows hold several — and writes without being wrapped in anything.

**A result style, opt-in.** Queries throw, the way the driver throws — which is what keeps them usable from a
`try`/`catch` and from a Spring `@Transactional` without either knowing this module exists. Where a failure
should be a value instead, there is a door for each width: `asResult()` for one query, `transactionResult` for
a transaction, `dbResult { }` for anything else.

## What it does not add

It wraps no query and renames no method. The terminal family is the driver's own — `fetchRows`,
`fetchObjects`, `fetchField`, `forEach*`, `update` — under those names, with those overloads, meaning those
things. Where the work is not a query at all, `db.execute { }` hands over the driver's own session operations
and gets out of the way. Nothing is delegated, so nothing can fall out of step with the layer beneath it.

There is no Spring integration module and none is planned. Running under a framework that owns its own
transactions means implementing `SessionProvider` against it and passing that to
`OctaviusClient.fromSessionProvider` — for Spring, under thirty lines over the driver's existing
`OctaviusTemplate` and a `PlatformTransactionManager`. `execute` delegates in one line; the timeouts do not,
Spring having a single one that means the transaction rather than the statement, so both `SET LOCAL`s are
yours to issue.

## What is thrown and what is returned

SQL the server would not parse, a row that does not fit the class it was asked for, a type the registry has
never heard of, a session that could not be obtained: thrown, being the same on every run. A violated
constraint, a deadlock, a serialization failure, a `RAISE EXCEPTION`, a statement that ran out of time: those
become a `Failure` once you have asked for one.

A `fetch*Strict` that found no row is thrown, and so is a non-nullable `T` over a `NULL`. Both are assertions
the calling code made about its own data. A lookup allowed to find nothing says so in its type instead —
`fetchRow` returns `Row?`, `fetchField<String?>` returns `null`.

## Project status

First released in 0.9.8, and on 1.0.0 with the rest of the repository since. What that number does and does
not promise is in the [repository README](../README.md#project-status). The [CHANGELOG](../CHANGELOG.md)
records the changes under a section of its own within each version, including the places this deliberately
parts company with octavius-database.

## Documentation

[The client's guides](../docs/client/README.md) cover what a signature cannot show; they assume the
[driver's](../docs/driver/README.md) and point back at them rather than restating them.

- [Quickstart](../docs/client/quickstart.md) — from a pool to a row, and the line that leaves your signatures
- [Queries](../docs/client/queries.md) — the builders, `QueryFragment`, `toSql`, per-query converters
- [Transactions and Failures](../docs/client/transactions-failures.md) — propagation, timeouts, and when a
  failure is a value
- [Transaction Plans](../docs/client/plans.md) — a sequence as data, and what fits what
- [`dynamic_dto`](../docs/client/dynamic-dto.md) — one column, several unrelated shapes
- [Annotation Scanning](../docs/client/scanner.md) — [`client-scanner`](../client-scanner/README.md), which
  registers your annotated types for you
- [Performance](../docs/client/performance.md) — what the layer costs, this stack beside Spring and JDBI, and
  composite or `dynamic_dto`

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
