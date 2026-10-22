# Octavius Client — Scanner

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/client-scanner)](https://central.sonatype.com/artifact/io.github.octavius-framework/client-scanner)

Finds the annotated classes in your packages and registers them, so that thirty types are named once instead
of thirty times.

> Part of [Octavius for PostgreSQL](../README.md), released with it and on the same version. Needs the
> [client](../client/README.md), which is what it registers into.

```kotlin
val db = OctaviusClient.fromDataSource(dataSource)
db.dynamicTypes.install()

val found = db.registerAnnotatedTypes("com.roma.domain", "com.roma.dto")
logger.info { "Octavius registered $found" }
```

## Why it is a module of its own

It does nothing the hand-written calls cannot: `typeManager.registerEnum`, `registerAutoComposite` and
`dynamicTypes.register` are the whole of what it ends up doing. What it needs that they do not is a way to walk
a classpath correctly — jars inside jars, the module path, a Spring Boot fat jar — and that is a dependency
([ClassGraph](https://github.com/classgraph/classgraph)) worth keeping off everyone who registers by hand.

The annotations it looks for are **not** here. They live in the multiplatform `pg-model` module, so a class
shared with another platform can carry them in `commonMain` and still be found on the JVM.

| Annotation            | Goes on      | Registers as                          |
|-----------------------|--------------|---------------------------------------|
| `@PgEnumType`         | an enum      | a PostgreSQL `ENUM` type              |
| `@PgCompositeType`    | a data class | a PostgreSQL `COMPOSITE` type         |
| `@DynamicallyMappable`| any class    | a `dynamic_dto` discriminator         |

`@PgEnumType` carries the same case conventions `registerEnum` takes, so an enum whose labels are lowercase in
the database says so rather than dropping out of the scan. It and `@PgCompositeType` derive the type name from
the class where none is given — `ScanRank` to
`scan_rank` — using the driver's own converter. `@DynamicallyMappable` states its name and always will: that
one is a discriminator **stored in the data**, so deriving it would mean a class rename silently orphaning
every row written before it.

## What it reports

A scan returns what it registered, and a scan that matched nothing logs a warning rather than passing quietly
— a package name with a typo in it otherwise registers nothing and says nothing.

It also checks each enum and composite name against the database, and lists in `ScanReport.unresolved` the ones
the database has no type for. **Reported, not refused.** Registering ahead of a type that does not exist yet is
a working flow: the converters survive a `reloadTypes()`, so a name missing at scan time is a question rather
than an answer. The other way to get there is a typo, which is otherwise silent until a query cannot map a
column — so the scan says it out loud and leaves the decision to you:

```kotlin
val found = db.registerAnnotatedTypes("com.roma.domain")
check(found.unresolved.isEmpty()) { "Octavius: no type in the database for ${found.unresolved}" }
```

Registration itself never checked, here or in the driver's own `registerEnum`; this does not change that, it
only says what it saw.

## What it does not do

It is not required. Registering by hand is the driver's own story and stays fully supported; this is for the
application with enough types that naming them individually is where the next one gets forgotten.

## Documentation

- [Annotation Scanning](../docs/client/scanner.md) — this page at length, and what `unresolved` is for
- [`dynamic_dto`](../docs/client/dynamic-dto.md) — what `@DynamicallyMappable` registers into
- [Type System](../docs/driver/type-system.md#registering-your-own-mappings) — the calls this is a shortcut for

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
