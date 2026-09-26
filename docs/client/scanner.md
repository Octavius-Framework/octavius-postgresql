# Annotation Scanning

*A boundary was walked, not consulted. The surveyor and the neighbours went the line together and put a hand on
each terminus in turn — and where a stone was gone, that went in the record as a stone that was gone. Nobody
concluded that the field had no edge there.*

`client-scanner` walks the packages you name, finds the annotated classes, and registers them — so thirty types
are named once instead of thirty times.

```kotlin
val db = OctaviusClient.fromDataSource(dataSource)
db.dynamicTypes.install()

val found = db.registerAnnotatedTypes("com.roma.domain", "com.roma.dto")
logger.info { "Octavius registered $found" }
```

```kotlin
dependencies {
    implementation("io.github.octavius-framework:client-scanner:2.3.0")
}
```

## Why It Is a Module of Its Own

It does nothing the hand-written calls cannot. `typeManager.registerEnum`, `registerAutoComposite` and
`dynamicTypes.register` are the whole of what it ends up doing, and registering by hand stays fully supported —
this is for the application with enough types that naming them individually is where the next one gets
forgotten.

What it needs that those do not is a way to walk a classpath **correctly**: jars inside jars, the module path,
a Spring Boot fat jar. That is a dependency ([ClassGraph](https://github.com/classgraph/classgraph)) worth
keeping off everyone who registers by hand, which is the whole reason for the separate coordinate.

## The Annotations

The annotations are **not** here. They live in the multiplatform `pg-model` module, which the driver takes
as an `api` dependency — so a class shared with another platform can carry them in `commonMain` and still be
found on the JVM. A desktop application and a browser extension reading the same DTOs was the case that forced
it: an annotation compiled only for the JVM cannot be written on a class in `commonMain` at all.

| Annotation             | Goes on      | Registers as                  |
|------------------------|--------------|-------------------------------|
| `@PgEnumType`          | an enum      | a PostgreSQL `ENUM` type      |
| `@PgCompositeType`     | a data class | a PostgreSQL `COMPOSITE` type |
| `@DynamicallyMappable` | any class    | a `dynamic_dto` discriminator |

`@PgEnumType` carries the same case conventions `registerEnum` takes, and they reach further than the column:
a scanned enum used inside a `dynamic_dto` payload is written under those same labels, with no serializer to
write and no `@Serializable` on the enum. Mark the property `@Contextual` and that is the whole of it — see
[What JSON Does Not Carry](dynamic-dto.md#what-json-does-not-carry).

It and `@PgCompositeType` derive the type name from the class where none is given — `ScanRank` to `scan_rank`
— using the driver's own converter. **`@DynamicallyMappable` states its name and always will**: that one is a
discriminator stored in the data, so deriving it would mean a class rename silently orphaning every row written
before it. See [Registering a Class](dynamic-dto.md#registering-a-class).

`@PgName` is the fourth annotation in that module and is not scanned for — it overrides one attribute's name
and the driver reads it during mapping.

## What a Scan Reports

```kotlin
data class ScanReport(
    val enums: List<RegisteredType>,
    val composites: List<RegisteredType>,
    val dynamicTypes: List<RegisteredType>,
    val unresolved: List<RegisteredType>
)
```

`total` and `isEmpty()` come with it. A scan that matched nothing [logs a warning](#what-a-scan-logs) rather
than passing quietly — a package name with a typo in it otherwise registers nothing and says nothing.

Each enum and composite name is checked against the database, and the ones the database has no type for go into
`unresolved`. **Reported, not refused.**

Registering ahead of a type that does not exist yet is a working flow: converters survive a `reloadTypes()`, so
a name missing at scan time is a question rather than an answer — you may be about to run the migration that
creates it. The other way to get there is a typo, which is otherwise silent until some query cannot map a
column. So the scan says it out loud and leaves the decision where it belongs:

```kotlin
val found = db.registerAnnotatedTypes("com.roma.domain")
check(found.unresolved.isEmpty()) { "Octavius: no type in the database for ${found.unresolved}" }
```

Registration itself never checked — not here and not in the driver's own `registerEnum`. This does not change
that; it only says what it saw.

`registerAnnotatedTypes` takes an optional `classLoader` for the container that does not hand its classes to
the context loader.

## What a Scan Logs

This is the only part of the client that logs at all — [the client itself writes
nothing](README.md#logging) — and it is on the class doing the work,
`io.github.octaviusframework.client.scanner.TypeScanner`. Four lines, and none of them per type:

| Level   | When                                | What it says                                                             |
|---------|-------------------------------------|--------------------------------------------------------------------------|
| `info`  | A scan that registered something    | The `ScanReport`, and the packages it walked                             |
| `debug` | The same scan                       | The three lists spelled out, class by class and name by name             |
| `warn`  | A scan that matched nothing at all  | That it found nothing, and that the package names are what to check      |
| `warn`  | Anything went into `unresolved`     | Which names the database has no type for, and the two ways that happens  |

The `info` line is the one worth keeping, and it is the whole of a successful startup:

```
Registered ScanReport(3 enums, 7 composites, 2 dynamic types) from com.roma.domain, com.roma.dto
```

`debug` under it is the same scan with the derived names filled in, which is where to look when a type
registered under a name you did not expect:

```
enums=[ScanRank -> scan_rank, Province -> province], composites=[Senator -> senator], dynamic=[Edict -> edict]
```

Both warnings say in a log what the report already says in code, and that repetition is the point. `unresolved`
is a list you can `check()` on and an empty scan is an empty `ScanReport` — but a value is only read by an
application that thought to read it, and the failure this module exists to make visible is the one nobody
thought about. A registration that quietly did nothing stays invisible until a query, months later, cannot map
a column.

Registration itself is not logged, per type or at all. Thirty types registered is the one `info` line above,
which is rather the point of the module.

## What It Does Not Scan

Converters. A `ResultConverter` or `ParameterConverter` is registered by hand, at startup, in one place — and
that is on purpose rather than for want of doing it.

A type is a **domain class**. It lives wherever the feature that owns it lives, and it is added by whoever adds
that feature, so the list of them is exactly the thing a person cannot be trusted to keep: the enum goes into a
new package and the registration call, three modules away, is not updated. Scanning answers that — the
declaration travels with the class instead of being tracked by something else.

A converter is not shaped like that. There are few of them, they are written deliberately, and they belong to
the wiring rather than to a feature. But the reason not to scan them is stronger than *they would not benefit*:
**for converters, registration order is meaning.** They are consulted newest-first and a later one wins, which
is the whole mechanism by which anything overrides anything — see
[the read SPI](../driver/type-system.md#how-a-converter-gets-chosen). A classpath scan has no defined order:
it is whatever the jars and the filesystem hand back, and it can differ between one build and the next. Scanning
them would make priority non-deterministic in the one place where priority is the only lever there is.

The previous generation scanned its `TypeHandler`s and was right to: those were resolved from a **map keyed by
OID and class**, one per type, no overlap and no order to get wrong. That is what changed. Until `canConvert`
stops being able to say yes to anything, the block that registers converters by hand is the only place their
order is visible, and a scanner would hide it.

## Next

- [`dynamic_dto`](dynamic-dto.md) — what `@DynamicallyMappable` registers into
- [Type System](../driver/type-system.md#registering-your-own-mappings) — the calls this is a shortcut for
