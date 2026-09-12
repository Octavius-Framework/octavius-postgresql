# Octavius — Pg Model

[![Maven Central](https://img.shields.io/maven-central/v/io.github.octavius-framework/pg-model)](https://central.sonatype.com/artifact/io.github.octavius-framework/pg-model)

The part of Octavius a class can carry when that class is not only the backend's.

> Part of [Octavius for PostgreSQL](../README.md), released with it and on the same version. It depends on
> nothing of Octavius', and everything else here depends on it — so it arrives with whichever artifact you
> took and is rarely named by hand.

Multiplatform: **JVM**, and **JS** for browser and Node. The driver reads it, the scanner scans for it, and a
`commonMain` shared with a frontend can declare it.

```kotlin
// commonMain — the same file the frontend compiles
@Serializable
@DynamicallyMappable("land_grant")
data class LandGrant(
    val province: String,
    @Contextual val iugera: BigDecimal,
    @Contextual val until: LocalDate
)
```

## What is in it

**The three annotations a scan looks for** — `@PgEnumType`, `@PgCompositeType` and `@DynamicallyMappable`, each
naming a type to register. They live here rather than in the scanner that looks for them, so a class shared
with another platform can carry them in `commonMain` and still be found on the JVM. What each one registers is
[the scanner's page](../docs/client/scanner.md#the-annotations).

**`@PgName`, which no scan reads.** It goes on a property rather than a class and names the composite
attribute or map key that property maps to, where the `camelCase` to `snake_case` convention does not —
`@PgName("gov")` on `governorName`. The driver's reflective mapping reads it, so it applies to a composite
registered with `registerAutoComposite` and to object-to-map conversion, scanner or no scanner. It is here for
the same reason as the others: the classes it goes on are the application's own, and those are often the
shared ones.

**A `BigDecimal` that `commonMain` can name.** On the JVM it is a `typealias` for `java.math.BigDecimal` and
nothing else — the same class the driver decodes `numeric` into, so nothing converts anywhere. On JS it wraps
the decimal's text, because JavaScript's `Number` is a 64-bit float and would round exactly what `numeric` was
chosen to keep.

**Serializers for the four types whose JSON form does not match their column form.** The driver maps all four
correctly in a column of their own and the default serializer loses them the moment the same value travels as
JSON instead — a `jsonb` column, or a `dynamic_dto` payload. Three separate ways of losing them:

- `BigDecimal` written as a JSON number that rounds to a `Double`, dropping exactly the digits `numeric` was
  chosen to keep.
- `LocalDate` / `LocalDateTime` / `Instant` holding `infinity`, written out as the year ±999999999 — a year no
  `date` holds, and not `infinity` in any case.
- **And the ordinary years too**, outside `0001`..`9999`, where the two spellings disagree in a way no single
  string satisfies. ISO-8601 puts a sign on a year past four digits and PostgreSQL reads that sign as the start
  of a timezone offset; ISO has a year zero and PostgreSQL has none, so BC years are off by one on top. Every
  ISO spelling out there is refused rather than misread — `+10000-01-02` as a timezone offset out of range,
  `0000-01-02` as a field out of range, `-0001-01-02` as invalid syntax — so `(payload->>'until')::date` does
  not fail quietly, it just fails.

The serializers write PostgreSQL's own text form and read either spelling back, so a payload built in SQL and
one written before these existed both still decode. `octaviusSerializersModule` is the set of them; they are
contextual, so they change nothing until a property asks with `@Contextual`.

**The infinity constants themselves** — `LocalDate.DISTANT_PAST` / `DISTANT_FUTURE` and their `LocalDateTime`
counterparts, plus `DateTimePeriod.INFINITY` / `MINUS_INFINITY` for an interval. kotlinx.datetime keeps the
same bounds on every platform and makes them `internal` on all of them, which is why they are written out here
rather than delegated to.

**`CaseConverter` and `CaseConvention`** — the rule that turns `ScanRank` into `scan_rank` and `Praetor` into
`PRAETOR`. The driver uses it to derive a type name where a registration gives none, and
`EnumWithCaseConventionSerializer` applies the same conventions to an enum on a platform that has no driver to
ask.

## Why it is a module of its own

An annotation is read by the scanner, a serializer by whatever builds the `Json`, and a `BigDecimal` property
by both ends of the wire. None of that needs a database connection, and a frontend that wants the shared class
should not be pulling a PostgreSQL driver in to get it.

So the rule runs one way: `pg-model` knows nothing about the driver, and the driver, client, scanner and
migrations all know about `pg-model`.

## Documentation

It has no guide of its own; each piece is documented where it is used.

- [Type System](../docs/driver/type-system.md#bigdecimal-in-a-shared-class) — `BigDecimal`, and the infinity
  constants beside it
- [`dynamic_dto`](../docs/client/dynamic-dto.md#what-json-does-not-carry) — what JSON loses, and the
  serializers that fix it
- [Annotation Scanning](../docs/client/scanner.md#the-annotations) — what each annotation registers

## License

Licensed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
