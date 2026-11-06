# Type System and Mapping

*When Rome took a province its first act was not to impose a template but to write one — a survey of every community in
it, its status and its obligations, drawn up on arrival because nobody back in Rome could have known in advance what was
there. Octavius does the same on its first connection to a database: it reads the catalog rather than assuming it, which
is why a type you created yourself is never an unknown OID.*

Type mapping in `octavius-postgresql` rests on a flexible **2-layer architecture** that keeps low-level binary wire communication cleanly separated from high-level mapping onto Kotlin objects.

Two things are worth internalizing before the details:

* **The type catalog is read from the database, not hardcoded.** On the first connection to a given database, the driver queries `pg_catalog` and builds a full picture of every type living there — including *your* enums, composites, domains, ranges and table row types.
* **`session.typeManager` is the door to that catalog, but the room behind it is shared.** The handle is per-session; the state is per-database and JVM-wide. Registering a converter through one session changes behavior for every other session pointing at the same database. See [Scope: a session handle over global state](#scope-a-session-handle-over-global-state).

Contents:
* [Where types come from](#where-types-come-from)
* [2-Layer architecture](#2-layer-architecture)
* [How a value actually travels](#how-a-value-actually-travels)
* [`TypeManager` — the entry point](#typemanager--the-entry-point)
* [Basic codecs](#basic-codecs)
* [How a converter gets chosen](#how-a-converter-gets-chosen)
* [Registering your own mappings](#registering-your-own-mappings)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## Where types come from

### The catalog load

PostgreSQL identifies every type by an **OID** (Object Identifier). Built-in OIDs are stable (`int4` is always `23`), but the OIDs of anything *you* create — `CREATE TYPE legio_status AS ENUM (...)`, a composite, a domain, even the implicit row type of a table — are assigned per database and per `CREATE`. They cannot be baked into a driver.

So the driver asks. On the first physical connection to a given database within the JVM, `GlobalCatalogStore.ensureLoaded` fires `CatalogLoader`, which reads the whole catalog in a **single** query. One round trip, paid by whoever connects first; every later connection to the same database reuses the result.

The loader deliberately skips noise: composites belonging to `pg_catalog` and `information_schema`, and all pseudo-types except `void`, `record`, `_record` and `unknown`. It also leaves out every temporary schema, whose types belong to one session where the catalog belongs to all of them — see [the search path](#what-is-per-session-the-search-path) for what that means for a temporary table.

What comes back is classified into a sealed `PgType` hierarchy:

| `PgType` variant    | Extra information carried         |
|:--------------------|:----------------------------------|
| `PgType.Base`       | —                                 |
| `PgType.Array`      | `elementOid`                      |
| `PgType.Composite`  | ordered `attributes` (name → OID) |
| `PgType.Enum`       | `values` in declaration order     |
| `PgType.Domain`     | `baseTypeOid`                     |
| `PgType.Range`      | `subtypeOid`                      |
| `PgType.Multirange` | `rangeOid`                        |
| `PgType.Record`     | the anonymous row pseudo-type     |
| `PgType.Void`       | return type of void functions     |

The result is a `TypeDictionary` — an **immutable snapshot** with pre-computed lookups by OID, by name+schema, array-by-element, range-by-subtype and multirange-by-range. Reloading swaps the whole snapshot atomically rather than mutating it in place, so readers never see a half-updated catalog.

### Codecs are synthesized for what the driver has never seen

Loading the catalog also rebuilds the `CodecDictionary`. An OID with no explicitly registered codec gets a **dynamic** one generated on the spot — but only if it belongs to one of the *structural* categories, the ones whose wire format the driver can derive from the catalog alone:

| Discovered type       | Synthesized codec       | Decodes to                           |
|:----------------------|:------------------------|:-------------------------------------|
| enum                  | `DynamicEnumCodec`      | `String`                             |
| domain                | `DynamicDomainCodec`    | *delegates to the base type's codec* |
| array                 | `DynamicContainerCodec` | `PgArray`                            |
| composite / table row | `DynamicContainerCodec` | `PgComposite`                        |
| anonymous `record`    | `DynamicContainerCodec` | `PgRecord`                           |
| range / multirange    | `DynamicContainerCodec` | `PgRange` / `PgMultirange`           |

This is why a type *you* create is never a hard failure: worst case you get the generic representation back (`PgComposite`, `String`, ...) and can map it upward yourself. The converter layer is what turns those into your own classes.

### Base types the driver does not implement

A `PgType.Base` is a different story. Its binary layout is specific to that one type and can't be inferred from the catalog, so it needs a hand-written codec — and only the ones in [Basic codecs](#basic-codecs) have one. Everything else is discovered by the loader, appears in the `TypeDictionary`, and still has **no codec**. Notably absent today:

`money`, `timetz`, `tsvector`, `tsquery`, `jsonpath`, `pg_lsn`, `pg_snapshot`/`txid_snapshot`, `aclitem`, `tid`/`xid`/`cid`, the `reg*` types (`regclass`, `regproc`, ...), `int2vector`/`oidvector`.

Selecting such a column throws `TypeException(MISSING_CODEC)` naming the OID, rather than returning something plausible-but-wrong. Two ways forward:

* **Cast in SQL** — `SELECT to_tsvector('latin') :: text` or `SELECT amount::numeric`. Cheap, and usually enough.
* **Write the codec** — implement `TypeCodec<T>` against PostgreSQL's binary format for that type and `registerCodec` it. See [Custom codecs](#custom-codecs).

The same applies inside containers: an `int4[]` decodes fine, a `tsquery[]` fails on its elements, since the array codec still has to decode each element through the element type's codec. It surfaces under a different name there, though — the gap is reported by the container's own decode, so you get `CodecException(DECODING)` rather than the `TypeException(MISSING_CODEC)` the bare column would have given you.

### Keeping the catalog fresh — `reloadTypes()`

The catalog snapshot is taken once. DDL executed **after** that point is invisible to the driver until you say so:

```kotlin
session.createNativeQuery("CREATE TYPE legio_status AS ENUM ('ON_CAMPAIGN', 'IN_GARRISON')").execute()
session.createNativeQuery("CREATE TYPE tribute AS (amount int, currency text)").execute()

session.reloadTypes() // re-reads pg_catalog for this database
```

Worth knowing about a reload:

* It is **global too** — it refreshes the shared catalog for the whole database, not just this session.
* `CREATE TABLE` also creates a type (the table's row type), so table DDL counts.
* Custom **codecs survive**: registered codecs are replayed and re-bound against the new OIDs.
* Custom **converters and composite and enum registrations carry over** — a reload replaces the types and the codecs, and nothing else in the catalog.
* In an application with a static schema, you typically never call it. In tests, migrations, or anything that creates types at runtime, call it once after the DDL.
* **It does not reach a query already running.** A statement in flight finishes against the catalog its terminal pinned; the reload reaches the terminals that start after it. See [Concurrency](concurrency.md#what-a-reload-does-not-promise).

### One catalog per database, not per URL

Catalogs are cached in `GlobalCatalogStore` under a `DatabaseKey` of **the servers + database name** — deliberately *not* the whole connection URL. A single server is its `host:port`; [several](initialization.md#several-servers) are a set, so a URL makes one key whatever order it lists them in.

Credentials, SSL settings and timeouts have no effect on the type catalog. Keying on them would fragment the cache (and would keep a password alive as a map key for the lifetime of the JVM). Practical consequences:

* Two HikariCP pools connecting as different users to the same database **share one catalog** — and therefore one set of registered converters.
* Registration performed through *any* pool's session is visible to the others.
* The servers of one list share one catalog, whichever of them a connection lands on. A server of another cluster is passed over rather than read through it — see [One catalog for the list](initialization.md#one-catalog-for-the-list).
* If your application connects to thousands of *different* databases at runtime (a per-tenant-database setup, say), call `GlobalCatalogStore.removeCatalog(url)` when you close a data source, so the catalog can be collected. With a static set of URLs — the normal case — leave it alone.

## 2-Layer architecture

1. **Codecs Layer (`TypeCodec<T>`)**
    * **Role:** the direct translation between basic Kotlin types and PostgreSQL's native binary format (`ByteArray` and `PgByteWriter`).
    * **Operation:** codecs work at a low level, serializing and deserializing with full awareness of PostgreSQL type OIDs. The interface is deliberately tiny — `pgTypeName`, `pgSchema`, `oid`, `kotlinClass`, `isDefaultForKotlinType`, plus the `fromBinary` / `toBinary` function pair.
    * **Registration:** every codec lands in the `TypeCatalog`, associated by Kotlin class or by the OID defined in the database.
    * **Errors:** anything thrown inside `fromBinary` / `toBinary` is caught and re-thrown as a `CodecException` carrying the type name, schema, OID and a truncated copy of the offending bytes.

2. **Converters Layer (`ResultConverter<S, T>` / `ParameterConverter<T>`)**
    * **Role:** a higher level of abstraction, mapping the intermediate structures codecs decode (`PgComposite`, `PgArray`, `PgRecord`, `Row`) onto whatever complex, user-defined structures you actually want.
    * **Operation:** handles reflective mapping onto classes (data classes), transformation into collections (`Collection<*>`), maps (`Map<String, Any?>`), and nested objects.
    * **Context:** `SerializationContext` and `DeserializationContext` recursively resolve and convert nested types within complex structures, bridging the object layer and the binary layer smoothly in both directions. Both expose `types` — a `TypeLookup`, the reading half — so a converter can resolve OIDs or build containers mid-conversion, and cannot register anything.
    * **Errors:** failures surface as a `MappingException` whose `path` accumulates the segment names it passed through — so a bad field five levels down in a nested composite tells you *which* field.

Thanks to this split, adding support for a specific custom PostgreSQL type is usually just writing a small, focused codec — the reflective work of wiring it into data classes and collections is handled automatically by the generic converter layer above it.

## How a value actually travels

```
 read   raw bytes ──▶ TypeCodec.fromBinary ──▶ intermediate value ──▶ ResultConverter ──▶ Senator
                      (chosen by column OID)   String, Int, PgComposite…   (at row.get<T>())

 write  Senator ──▶ ParameterConverter ──▶ intermediate value ──▶ TypeCodec.toBinary ──▶ raw bytes
                    (chosen by Kotlin class)  PgComposite, String…   (by OID, else by class)
```

### Reading (database → Kotlin)

1. A row arrives. For **every** column, the driver looks up a codec by the column's OID and decodes the bytes immediately — a `Row` is fully decoded at construction time. Missing codec for an OID means `TypeException(MISSING_CODEC)`.
2. The results are *intermediate* values: `Int`, `String`, `PgComposite`, `PgArray`, and so on.
3. Nothing else happens until you ask. `row.get<Senator>("senator")` hands the intermediate value, the requested `KType` and the source `PgType` to the `ResultMapper`.
4. The mapper picks a `ResultConverter` (see [precedence](#how-a-converter-gets-chosen)) and calls it. Converters recurse through `context.convert(...)` for nested attributes, elements and fields.
5. If no converter matches but the value is already an instance of the requested class, it's passed straight through. Otherwise: `MappingException(NO_CONVERTER_FOUND)`.
6. A `null` requested as a non-nullable Kotlin type raises `MappingException(REQUIRED_ATTRIBUTE_MISSING)` rather than a `NullPointerException` somewhere later.

Because step 1 is eager and steps 3–5 are lazy, the cost of `row.get<T>()` is conversion only — and asking the same row for two different shapes (`get<Map<String, Any?>>` and `get<Senator>`) decodes the wire data just once.

### Writing (Kotlin → database)

Parameters are serialized before anything is sent, and the OID each one is declared with in `Parse` is the client's choice, not the server's. [One default per Kotlin class](#one-default-per-kotlin-class) says how it is chosen and when you have to state it yourself.

## `TypeManager` — the entry point

`TypeManager` is the public, high-level API over the catalog the driver holds for a database. You reach it from any session:

```kotlin
val session = dataSource.getOctaviusSession()
session.typeManager.registerEnum<LegioStatus>()
```

### What it exposes

| Member                                  | Purpose                                                                                        |
|:----------------------------------------|:-----------------------------------------------------------------------------------------------|
| `resolveOid(typeName, schema, isArray)` | Resolves a type name to its OID, honoring this session's `search_path`.                        |
| `registerCodec(codec)`                  | Binary-level mapping for a PostgreSQL type.                                                    |
| `registerResultConverter(converter)`    | Database → Kotlin object mapping.                                                              |
| `registerParameterConverter(converter)` | Kotlin object → database parameter mapping.                                                    |
| `registerEnum<T>(...)`                  | Registers both directions for a Kotlin enum in one call.                                       |
| `registerAutoComposite<T>(...)`         | Maps a data class to a PostgreSQL composite type reflectively.                                 |
| `attach(type) { }`                      | Keeps a layer's own value per database, read back as `catalog.attachment(type)`.               |
| `dictionary`                            | The types the catalog describes — `getPgType(oid)`, `getArrayType(...)`, `forEachType { }`.    |
| `codecs`                                | Codec lookups — `getCodecByOid(...)`, `getCodecByClass(...)`.                                  |
| `catalog`                               | The dictionaries, converters, registrations and attachments, as one immutable value.           |
| `containers`                            | `ContainerFactory` — builds `PgComposite`, `PgRange`, `PgMultirange` instances by name or OID. |
| `detached()`                            | A `TypeLookup` with no session behind it, for something outliving the session it came from.    |

Everything but the `register*` and `attach` rows reads. They are separate types, not a convention: `TypeLookup`
carries `catalog`, `dictionary`, `codecs`, `containers` and `resolveOid`, and **no `register*` or `attach` at
all**. A conversion reaches it as `context.types`, so registering a type, a codec or a converter from inside a
converter is not something the API allows — it could not have affected the conversion it was made from, the
catalog having been pinned before the statement went out, and would have reached every other session on the
database instead.

### Scope: a session handle over global state

This is the part that surprises people, so it's worth stating bluntly:

> **`typeManager` is created per session, but the catalog behind it is shared per database across the whole JVM.**

`OctaviusSessionImpl` constructs a fresh `TypeManager` for each session, but hands it the `CatalogHolder` that `GlobalCatalogStore` keeps for that database. Nothing is copied. So:

* Registering an enum, composite, converter or codec through **one** session affects **every** session on that database — those already open, those still to be opened, and those borrowed from a different connection pool.
* Closing the session that performed the registration changes nothing; the registration outlives it.
* The type catalog itself is loaded once and shared the same way.

The practical shape this leads to is a **single registration step at application startup**, not per-request wiring:

```kotlin
// Once, at startup — e.g. in a Spring @PostConstruct or an init block
dataSource.getOctaviusSession().use { session ->
    session.typeManager.registerEnum<LegioStatus>()
    session.typeManager.registerAutoComposite<Address>()
    session.typeManager.registerAutoComposite<SenatorProfile>()
}
// Every session opened afterwards already knows about all three.
```

Doing it per request isn't just wasted work — the catalog's converter lists are copy-on-write, and re-registering the same converter **prepends another copy** every time. The newest one wins so behavior stays correct, but the list grows without bound and lookups get slower. Register once. What a registration or a reload promises the threads already querying is in [Concurrency](concurrency.md#what-is-shared-beyond-the-connection).

### What *is* per-session: the search path

The one session-scoped ingredient is the schema search path. A type name given without a schema — to `registerEnum`,
`withPgType` or `resolveOid` — resolves to the type PostgreSQL would resolve it to in this session, so two sessions
with different paths can resolve one name to different OIDs over the same catalog. A schema named explicitly always
wins, and throws if the type is not there.

```kotlin
session.createNativeQuery("SET search_path TO imperium, public").execute()

session.typeManager.resolveOid("legio_status")             // resolved via search_path
session.typeManager.resolveOid("legio_status", "imperium") // explicit schema, search_path ignored
```

A name the path does not reach is refused with `TypeException(TYPE_NOT_FOUND)`, and the refusal names the schemas that
have it: *"Type 'legio_status' is not on the search path [pg_catalog, public], but schema 'imperium' has it. Name the
schema, or put it on the search path."* Name it — `registerEnum<LegioStatus>(schema = "imperium")`,
`withPgType("legio_status", schema = "imperium")` — or put it on the path with `SET search_path`, or as a
[startup parameter](initialization.md#startup-parameters) so that every session opens with it. `session.searchPath`
reads the path back and has no setter.

Three things set the driver's answer apart from the server's:

* **Temporary types are outside the catalog**, a temporary table's row type among them, even for a table made `OF` a
  permanent type. The driver never resolves a name to one, and a column of one, such as the whole row of a temporary
  table, fails when the result is described, `reloadTypes()` or not. Cast it to a permanent composite with the same
  attributes: `SELECT e::imperium.edict FROM edicts e`.
* **`SET ROLE`** is not reported, so `$user` keeps meaning the session user. Where schemas are named after roles, set
  the path in plain names along with the role: `SET ROLE legatus; SET search_path = legatus, public`.
* **`USAGE`.** A schema on the path the current role may not use is skipped by the server, not by the driver.

### Query-scoped overrides

When a global registration is too big a hammer, converters can be scoped to a **single query**. Each query object keeps a list of its own, consulted ahead of the registered ones:

```kotlin
val senators = session.createNamedQuery("SELECT * FROM senators WHERE ordo = @ordo")
    .registerResultConverter(OneOffSenatorConverter())   // this query only
    .fetchObjects<Senator>("ordo" to "patrician")
```

A query's own converters are consulted first and the catalog's after them, so a query-scoped converter overrides a registered one for that query and disappears with the query object. Nothing global is mutated.

They take effect from the next terminal, which is where the catalog for a run is pinned. Rows already in hand were mapped against the catalog *their* terminal pinned, so registering a converter after a fetch does not reach them — run the query again to map them through it. It takes a deliberately odd shape to notice: a query kept and reconfigured between fetches.

| Registered on              | Visible to                                              | Lifetime                                       |
|:---------------------------|:--------------------------------------------------------|:-----------------------------------------------|
| `query.register*Converter` | that one query instance                                 | until the query is discarded                   |
| `session.typeManager.*`    | every session on the same servers + database in the JVM | lifetime of the JVM (or until `removeCatalog`) |

## Basic codecs

The `io.github.octaviusframework.driver.codec` package ships the codecs translating between PostgreSQL and Kotlin types.

| PostgreSQL Type                                             | Kotlin Type                                                               | Notes                                            |
|:------------------------------------------------------------|:--------------------------------------------------------------------------|:-------------------------------------------------|
| `int2`                                                      | `Short`                                                                   |                                                  |
| `int4`                                                      | `Int`                                                                     |                                                  |
| `int8`                                                      | `Long`                                                                    |                                                  |
| `float4`                                                    | `Float`                                                                   |                                                  |
| `float8`                                                    | `Double`                                                                  |                                                  |
| `numeric`                                                   | `java.math.BigDecimal`                                                    |                                                  |
| `text`, `varchar`, `unknown`, `bpchar` (`character`)        | `String`                                                                  |                                                  |
| `json`, `jsonb`                                             | `String`                                                                  | Processed later by JSON converters               |
| `timestamptz`                                               | `kotlin.time.Instant`                                                     | <sup>1</sup>                                     |
| `timestamp`                                                 | `kotlinx.datetime.LocalDateTime`                                          | <sup>1</sup>                                     |
| `date`                                                      | `kotlinx.datetime.LocalDate`                                              | <sup>1</sup>                                     |
| `time`                                                      | `kotlinx.datetime.LocalTime`                                              |                                                  |
| `interval`                                                  | `PgInterval`                                                              | <sup>1</sup> Sealed — see [PgInterval](#pginterval) |
| `bool`                                                      | `Boolean`                                                                 |                                                  |
| `bytea`                                                     | `ByteArray`                                                               |                                                  |
| `uuid`                                                      | `kotlin.uuid.Uuid`                                                        |                                                  |
| `xml`                                                       | `String`                                                                  |                                                  |
| `bit`, `varbit`                                             | `java.util.BitSet`                                                        |                                                  |
| `inet`, `cidr`, `macaddr`, `macaddr8`                       | `String`                                                                  | String preserves original notation e.g., `/24`   |
| `point`, `line`, `lseg`, `box`, `path`, `polygon`, `circle` | `PgPoint`, `PgLine`, `PgLseg`, `PgBox`, `PgPath`, `PgPolygon`, `PgCircle` | Mapped to native driver geometric data classes   |
| `void`                                                      | `Unit`                                                                    | Return type of void functions (e.g. `pg_notify`) |
| `oid`, `name`, `"char"`                                     | `Int`, `String`, `String`                                                 | Internal PostgreSQL types                        |
| `array`                                                     | `PgArray`                                                                 | Evaluated at runtime                             |
| `composite`, `record`                                       | `PgComposite`, `PgRecord`                                                 | Evaluated at runtime                             |
| `enum`                                                      | `String`                                                                  | Evaluated at runtime                             |
| `domain`                                                    | *(Base type)*                                                             | Delegates to the codec of the underlying type    |

All of these carry a fixed, hardcoded `oid`, so they work before the catalog is even loaded. The rows marked *"Evaluated at runtime"* are the dynamic codecs from [the catalog load](#codecs-are-synthesized-for-what-the-driver-has-never-seen) — those OIDs differ per database, so there is nothing to hardcode. Anything not on this list and not structural has no codec at all: see [Base types the driver does not implement](#base-types-the-driver-does-not-implement).

### One default per Kotlin class

Several PostgreSQL types map onto the same Kotlin class — `text`, `varchar`, `bpchar`, `json`, `jsonb`, `xml`, `inet`, `name` all decode to `String`. Decoding is unambiguous, because the codec is chosen by the column's OID. Encoding is not: given a bare `String` parameter, which type should it go out as?

The tie-break is `isDefaultForKotlinType`. Exactly one codec claims each Kotlin class, and it decides whenever the driver has no OID to work from:

| Kotlin class                                                                             | Default PostgreSQL type                                                        | Also decoded by (not default)                                                                                          |
|:-----------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------|:-----------------------------------------------------------------------------------------------------------------------|
| `String`                                                                                 | `text`                                                                         | `varchar`, `bpchar`, `unknown`, `json`, `jsonb`, `xml`, `inet`, `cidr`, `macaddr`, `macaddr8`, `name`, `"char"`, enums |
| `Int`                                                                                    | `int4`                                                                         | `oid`                                                                                                                  |
| `java.util.BitSet`                                                                       | `bit`                                                                          | `varbit`                                                                                                               |
| `Short`, `Long`, `Float`, `Double`, `BigDecimal`, `Boolean`, `ByteArray`, `Uuid`, `Unit` | `int2`, `int8`, `float4`, `float8`, `numeric`, `bool`, `bytea`, `uuid`, `void` | —                                                                                                                      |
| `Instant`, `LocalDateTime`, `LocalDate`, `LocalTime`, `PgInterval`                       | `timestamptz`, `timestamp`, `date`, `time`, `interval`                         | —                                                                                                                      |
| `PgPoint`, `PgLine`, `PgLseg`, `PgBox`, `PgPath`, `PgPolygon`, `PgCircle`                | `point`, `line`, `lseg`, `box`, `path`, `polygon`, `circle`                    | —                                                                                                                      |

This is the **common** path, not a rare fallback. The client decides every parameter's OID and declares it in `Parse`, and being binary in both directions it cannot leave the choice to the server: a binary representation belongs to one type, so the codec has to be picked before a single byte is written. pgjdbc's `stringtype=unspecified` defers that choice through the text format; here it is made per value instead. The one exception is a null with no type hint: it's declared as OID `0` and carries no bytes at all, so the server resolves it from context.

Where an OID **is** known, and the default therefore doesn't apply:

* the value was wrapped in `PgTyped`;
* the value is — or converts into — a `PgContainer` (`PgComposite`, `PgArray`, `PgRange`, ...), which carries its own OID and is therefore self-describing;
* the value is *nested* — a composite attribute or an array element — where the OID comes from the catalog definition of the enclosing type;
* a `ParameterConverter` supplied one through `getDefaultTypeName()`, which is how a converter whose output is a plain scalar declares its target type. Registered enums rely on this, as does the built-in `jsonb` converter.

Everywhere else, a bare `String` goes out as `text` and a bare `Int` as `int4`. That's right far more often than not, but it's also why sending a `String` into a `jsonb`, `inet` or unregistered enum column needs the type stated explicitly:

```kotlin
// Without the hint this String is declared as `text` in Parse
session.createNamedQuery("INSERT INTO senators (dossier) VALUES (@dossier)")
    .executeUpdate("dossier" to jsonString.withPgType("jsonb"))
```

### Infinity Values for Date, Time and Interval

<sup>1</sup> PostgreSQL's special `infinity` / `-infinity` values are fully supported for the date, timestamp and interval types:

| PostgreSQL Type | Special Values          | Kotlin                                                       |
|-----------------|-------------------------|--------------------------------------------------------------|
| `date`          | `infinity`, `-infinity` | `LocalDate.DISTANT_FUTURE`, `LocalDate.DISTANT_PAST`         |
| `timestamp`     | `infinity`, `-infinity` | `LocalDateTime.DISTANT_FUTURE`, `LocalDateTime.DISTANT_PAST` |
| `timestamptz`   | `infinity`, `-infinity` | `Instant.DISTANT_FUTURE`, `Instant.DISTANT_PAST`             |
| `interval`      | `infinity`, `-infinity` | `PgInterval.Infinity`, `PgInterval.MinusInfinity`            |

kotlinx-datetime has no such constants of its own: the four on `LocalDate` and `LocalDateTime` are extension
properties from `pg-model`, imported from `io.github.octaviusframework.type.datetime`, while `Instant`'s two are the
standard library's. Each is the extreme value its class can hold, so an infinity is recognised by comparing with the
constant. `interval` has cases of its own instead; see [PgInterval](#pginterval).

### Numeric (BigDecimal) Special Values

`BigDecimal` has no non-finite values, so reading a `numeric` that holds `NaN`, `Infinity` or `-Infinity` throws `CodecException(DECODING)` rather than produce a number it is not.
Where those values are meant, use `float4` / `float8`, which map to `Float` / `Double`, or [override the `numeric` codec](#overriding-a-built-in) (OID 1700) with a class that can hold them.

### `BigDecimal` in a Shared Class

A class in `commonMain` cannot name `java.math.BigDecimal`, so `pg-model` carries `io.github.octaviusframework.type.BigDecimal` — a `typealias` for it on the JVM, and the decimal's text on JS, where a `Number` is a 64-bit float and would round exactly what `numeric` was chosen to keep.
Being an alias and not a wrapper, it *is* the class the codec above produces: nothing converts anywhere, and backend-only code can go on writing either name.

Both the infinities above and this one lose their meaning once the value travels as JSON rather than as a column — a `jsonb` column, or a `dynamic_dto` payload. `pg-model` carries the serializers that fix that; see [What JSON Does Not Carry](../client/dynamic-dto.md#what-json-does-not-carry).

### PgInterval

By default, PostgreSQL's `interval` type maps to the driver's own `PgInterval` class rather than to a stock Kotlin type like `kotlin.time.Duration` or `kotlinx.datetime.DateTimePeriod`.

`Duration` holds only absolute time, so months and days would need an assumed length. `DateTimePeriod` keeps them apart, but counts the time part in nanoseconds of a `Long`, which runs out past about 2.5 million hours where `interval` does not, and has no `infinity` beyond the `pg-model` marker the conversions below use.

So when you extract an interval, you get a `PgInterval` holding the raw database representation as-is, and
explicit extensions — `toDurationApproximate()`, `toDurationExact()`, `toDateTimePeriod()` — so you decide how
(and whether) to collapse it.

It is a **sealed interface** rather than a single class, because PostgreSQL's `interval` is three things and only
one of them has components to read:

```kotlin
sealed interface PgInterval {
    data class Finite(val time: Long, val days: Int, val months: Int) : PgInterval  // time in microseconds
    data object Infinity : PgInterval
    data object MinusInfinity : PgInterval
}
```

So `interval.months` does not compile — the fields live on `Finite`, and getting at them means saying what to do
about the other two:

```kotlin
val term = session.createNativeQuery("SELECT term FROM magistracies WHERE id = $1")
    .fetchFieldStrict<PgInterval>(id)

when (term) {
    is PgInterval.Finite      -> "${term.months} months, ${term.days} days"
    PgInterval.Infinity       -> "for life"
    PgInterval.MinusInfinity  -> "expired before it began"
}
```

That is the whole reason for the shape. A finite interval and an unbounded one are not the same kind of value,
and the alternative — `Finite` alone, with `infinity` smuggled in as `Long.MAX_VALUE` microseconds — is the
arrangement `date` and `timestamp` are stuck with, where nothing in the type says the sentinel exists and
arithmetic on it silently produces nonsense. Here the compiler asks.

The conversions carry the infinities across rather than refusing them: `toDateTimePeriod()` gives
`DateTimePeriod.INFINITY` and `DateTimePeriod.MINUS_INFINITY` (from the same `pg-model` package as the date constants),
`toDurationExact()` and `toDurationApproximate()` give `Duration.INFINITE` and `-Duration.INFINITE`, and the extensions
below turn each of those back into `PgInterval.Infinity` and `PgInterval.MinusInfinity`.

The same extensions run the other way, for the parameter you are about to send:

| Going out                            | Produces                                                                            |
|:-------------------------------------|:------------------------------------------------------------------------------------|
| `DateTimePeriod.toPgInterval()`      | Years folded into months; days and time carried across as they stand.               |
| `Duration.toPgIntervalExact()`       | Time only — `months` and `days` stay zero, however long the duration is.            |
| `Duration.toPgIntervalApproximate()` | Split into months of 30 days and days of 24 hours, the way `justify_interval` does. |

`toPgIntervalExact()` is the one to reach for by default. A `Duration` is a claim about elapsed time; splitting `60.days` into "2 months" is a claim about the calendar that it never made, and the approximate form exists for when you do want that shape and accept the assumption under it.

Going the other way, `toDurationExact()` is the one that refuses ordinary values: an interval carrying months or days has no fixed length in absolute time, so it throws `IllegalArgumentException` rather than pick a convention on your behalf. That is easier to run into than it sounds — `age(a, b)` comes back as `1 year 7 mons 15 days`, and even a plain `a - b` between two timestamps counts in days. Keep it for intervals you know are pure time, and reach for `toDurationApproximate()` or `toDateTimePeriod()` wherever calendar units can appear.

If your application consistently wants, say, an approximate `Duration`, a `ResultConverter` registered under `PgInterval::class` intercepts and converts `PgInterval` values everywhere, automatically — `Finite` and both infinities alike, a converter under a sealed class being offered [every class below it](#how-a-converter-gets-chosen).

### PgTyped

`PgTyped` wraps a value to declare the PostgreSQL type it goes out as, wherever the driver cannot work the type out or
works out one the server will not take:

* an empty collection, or one holding only nulls, whose element type erasure has removed —
  [Empty collections need their type stated](arrays-ranges-json.md#empty-collections-need-their-type-stated);
* a `String` bound for `jsonb`, `json`, `inet` or an enum you have not registered, which would otherwise go out as
  `text` — [One default per Kotlin class](#one-default-per-kotlin-class);
* a `null` passed to an overloaded routine, so that PostgreSQL can pick the variant —
  [Typed NULLs](functions-procedures.md#typed-nulls-for-overloaded-routines);
* a range its element class does not settle: one over a subtype that also has a custom range type
  ([Ranges](arrays-ranges-json.md#ranges-and-multiranges)), or one over a composite
  ([`Range<Tribute>`](composites-reflection.md#getdefaulttypename-is-not-for-the-composite)).

Wrap any value with the `.withPgType(...)` extension functions:
* `value.withPgType(PgStandardType.INT4_ARRAY)`
* `value.withPgType("legio_status")` — a custom enum type, say, for a legion's current campaign status
* `value.withPgType("legio_status", schema = "imperium", isArray = true)` — pinned to a schema, as an array

The name is resolved through the session's search path while the parameter is serialized, and the resulting OID is what
the driver declares in `Parse`. That OID also selects the **codec that encodes the value**, so it has to be a type that
codec can produce from the class you are actually holding, and `withPgType` is not a cast:

|               | Says                         | Where it acts                      |
|:--------------|:-----------------------------|:-----------------------------------|
| `withPgType`  | "this is what I am sending"  | Client, before anything is written |
| A cast in SQL | "turn what I sent into this" | Server, on the value it received   |

`PgTyped` resolves an ambiguity and does not convert: a `List<Int>` named `INT8_ARRAY`
[fails before anything is sent](arrays-ranges-json.md#writing), because the `int8` codec encodes `Long`. Converting what
you sent into something else is what a SQL cast does, on the server. Nesting one `PgTyped` inside another throws — the
wrapper is meant to be the outermost layer. Data classes registered with `registerAutoComposite` get their type attached
automatically, so wrapping them by hand is unnecessary.

## How a converter gets chosen

**Result converters** are indexed by `supportedSourceClass` — the runtime class of the *decoded* value, not the class you asked for. A sealed class stands for every class below it, since a decoded value is always one of those: a converter registered under `PgInterval` is offered `PgInterval.Finite`, `PgInterval.Infinity` and `PgInterval.MinusInfinity`. A class below an open one is not covered, there being no list of them to read. Lookup goes:

1. The query's own converters, newest first: those registered for the source class or a sealed class above it, then those registered under `Any::class`.
2. Then the catalog's, registered for the source class or a sealed class above it, **newest first**, taking the first whose `canConvert(sourceClass, expectedType, sourceType, context)` returns `true`. A converter under a sealed class and one under a class below it are one queue, so whichever was registered later is asked first.
3. Then the catalog's `Any::class` converters, again newest first — a catch-all slot for converters that key off the PostgreSQL type rather than the decoded class.
4. Then the identity fallback, then `MappingException(NO_CONVERTER_FOUND)`.

**Parameter converters** live in one flat list, newest first, and the first whose `canConvert(sourceClass, expectedOid, context)` returns `true` wins. The default `canConvert` accepts exact class matches and subclasses, so a converter for a sealed base type covers its subclasses.

Because `canConvert` receives both the expected Kotlin type *and* the source `PgType`, a converter can be as narrow as you like — matching only `tribute` composites in the `imperium` schema, for example — and simply decline everything else.

**Keep converters out of the walk.** Every converter ahead of the one that claims a value is asked about that value, and
the question is per value rather than per row or per query — a nested composite is several values, each walking the list
again. So index a converter under the `supportedSourceClass` it actually handles: one registered under its real class is
not in the walk at all for values of other classes, while `Any::class` buys the catch-all slot at the price of being
asked about everything. Inside `canConvert`, compare the `PgType` first and reach for `expectedType.classifier` only once
that has matched — the type is in hand and comparing it is a field comparison, while the classifier goes through
`kotlin-reflect`.

## Registering your own mappings

All of these go through `session.typeManager` and are, again, **global for the database**. Do them once at startup, after any DDL that creates the types involved.

### Enums

```kotlin
enum class LegioStatus { ON_CAMPAIGN, IN_GARRISON, DISBANDED }

session.typeManager.registerEnum<LegioStatus>()
```

One call registers both directions. Defaults:

* **Type name** — the class name converted from `PascalCase` to `snake_case`: `LegioStatus` → `legio_status`. Override with `typeName = "..."`, and pin the schema with `schema = "..."`.
* **Value naming** — `kotlinConvention = PASCAL_CASE`, `pgConvention = SNAKE_CASE_UPPER`, i.e. labels are expected to be uppercase in the database (`'ON_CAMPAIGN'`).

For the common style where PostgreSQL labels are lowercase, state the conventions:

```kotlin
// Kotlin: ON_CAMPAIGN   PostgreSQL: 'on_campaign'
session.typeManager.registerEnum<LegioStatus>(
    pgConvention = CaseConvention.SNAKE_CASE_LOWER,
    kotlinConvention = CaseConvention.SNAKE_CASE_UPPER
)
```

Available conventions are `SNAKE_CASE_UPPER`, `SNAKE_CASE_LOWER`, `PASCAL_CASE` and `CAMEL_CASE`. A database label with
no matching Kotlin constant throws during deserialization rather than falling back silently.

A type name is registered for one enum, and an enum under one type name with one pair of conventions. A second enum
under a taken name, or a registered enum under another name or other conventions, is refused with
`InvalidOperationException(INVALID_ARGUMENT)`; the same registration again changes nothing. Names are compared as
written, so `rank` and `provincia.rank` can stand for two enums: read as `Any`, a value takes the enum registered under
its schema-qualified name, and failing that the one registered under the bare name, whichever was registered first —
as [composites](composites-reflection.md#composites-onto-data-classes) do.

### Composites (data classes)

```kotlin
data class Address(val city: String, val street: String)

session.typeManager.registerAutoComposite<Address>()
```

One call registers both directions, and the type name defaults to the class name in `snake_case`, as with enums.
[Composites and Reflective Mapping](composites-reflection.md#composites-onto-data-classes) is the page for the rest of
it: what the reflective mapper reads off your class, why a row needs no registration when a composite does, the whole
missing-value matrix, and a hand-written converter pair for a composite.

### Custom converters

When reflection isn't the mapping you want — a legacy column layout, a value class, a JSON payload you want as your own DTO — write the converter explicitly. A `@Serializable` class stored as `jsonb`:

```kotlin
@Serializable
data class Dossier(val status: String, val province: Int)

class DossierResultConverter : ResultConverter<String, Dossier> {
    override val supportedSourceClass = String::class

    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = (sourceType.name == "json" || sourceType.name == "jsonb") &&
                 expectedType.classifier == Dossier::class

    override fun convert(
        source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Dossier = Json.decodeFromString(source)
}

class DossierParameterConverter : ParameterConverter<Dossier> {
    override val supportedClass = Dossier::class

    override fun convert(source: Dossier, expectedOid: Int, context: SerializationContext): Any =
        Json.encodeToString(source)

    // The String above would go out as text, which a jsonb column refuses.
    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("pg_catalog", "jsonb")
}

session.typeManager.registerResultConverter(DossierResultConverter())
session.typeManager.registerParameterConverter(DossierParameterConverter())

session.createNativeQuery("INSERT INTO dossiers (id, data) VALUES ($1, $2)").executeUpdate(1, dossier)
val loaded: Dossier = session.createNativeQuery("SELECT data FROM dossiers WHERE id = $1").fetchField(1)
```

`getDefaultTypeName` is what makes the `INSERT` work. The converter hands on a plain `String`, which with [no OID
known](#one-default-per-kotlin-class) would go out as `text`, and a `jsonb` column refuses that with
`StatementException(DATA_TYPE_ERROR)`. It is asked only while the OID is still unknown and only for output that is not a
`PgContainer`, which carries its own, so a converter producing a composite, an array or a range never has it consulted.
[Writing the converters by hand](composites-reflection.md#writing-the-converters-by-hand) has the pair for a composite,
and [the one place](composites-reflection.md#getdefaulttypename-is-not-for-the-composite) its `getDefaultTypeName` still
matters.

The `canConvert` above checks both the PostgreSQL type and the Kotlin type, and each check alone leaks:

* **The PostgreSQL type alone** makes the converter global. Result converters are indexed by the decoded class, `String`
  for every `json` and `jsonb` value, and the newest registration there is asked first, so this one answers every read of
  a JSON column in the database, composite attributes included, ahead of the built-in `JsonElementConverter`. It fails
  loudly at least: the mapper checks what a converter produced against the type asked for, so `row.get<JsonObject>()`,
  and even `row.get<String>()`, on any JSON column raises `MappingException(CONVERSION_ERROR)`.
* **The Kotlin type alone** reaches sideways. The converter is offered every column that decodes to a `String` — `text`,
  `xml`, `inet`, unregistered enums — and a `text` column holding JSON comes back as a `Dossier` without an error.
  Nothing downstream can catch that, because the converter returns exactly the class requested; only its own predicate
  knows the column was the wrong one.

The same holds for any source class: `PgComposite` is the bucket of every composite in the database.

### Custom codecs

Reach for a codec only when you need to change how the **bytes** are read or written — a PostGIS type, a domain you want decoded to a value class, or replacing a built-in.

```kotlin
class CircleCodec : TypeCodec<Circle> {
    override val pgTypeName = "circle"
    override val oid: Int? = null              // resolved from the catalog instead
    override val kotlinClass = Circle::class
    override val fromBinary: (ByteArray, Int, Int) -> Circle = { data, offset, len -> decodeCircle(data, offset, len) }
    override val toBinary: (Circle, PgByteWriter) -> Unit = { value, writer -> encodeCircle(value, writer) }
}

session.typeManager.registerCodec(CircleCodec())
```

Both halves of the body have primitives to work against, and PostgreSQL's binary format is big-endian throughout.
Writing goes through the `PgByteWriter` handed to `toBinary`: `writeByte`, `writeShort`, `writeInt`, `writeLong`,
`writeFloat`, `writeDouble`, `writeBytes`, plus the `reserveLengthInt` / `fillLengthInt` pair for a block whose length
is only known once it has been written. Reading is the mirror of it, as extensions on `ByteArray`:

```kotlin
import io.github.octaviusframework.driver.io.getIntBE
import io.github.octaviusframework.driver.io.getDoubleBE   // also getShortBE, getLongBE, getFloatBE

private fun decodeCircle(data: ByteArray, offset: Int, len: Int) = Circle(
   x = data.getDoubleBE(offset),
   y = data.getDoubleBE(offset + 8),
   r = data.getDoubleBE(offset + 16)
)
```

Read at `offset` rather than from the start: the array `fromBinary` receives holds the whole current row and not just
your column, so `offset` and `len` are what delimit your value in it. It is also the connection's own buffer, reused row
after row for anything up to `maxCachedRowSize` — a row above that gets an array of its own, and nothing tells you which
you were handed — so decode what you need and never keep the array.

A codec is bound to OIDs in one of three ways, depending on what it declares:

| Declared                     | Binding behavior                                                                                                                                                                 |
|:-----------------------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `oid` set                    | Bound directly to that OID. No catalog lookup, works before the catalog is loaded. `0` is refused with `InvalidOperationException(INVALID_ARGUMENT)`: no type has it.            |
| `oid` null, `pgSchema` set   | The OID is resolved from the catalog at registration time; an unknown name throws `TypeException`.                                                                               |
| `oid` null, `pgSchema` empty | Bound to **every** OID in the catalog whose type name matches, across all schemas. The OID used for outbound parameters is resolved in-flight against the session's search path. |

Naming a schema the catalog does not have is refused at the registration. A reload that later leaves a registered codec bound to nothing does not raise — the database changing is not the registration's fault — but it says so at `warn`, because otherwise the column simply comes back in a different shape. The `oid` row is the one that goes stale that way: a type dropped and recreated has a new OID, and a codec pinned to the old one is never reached again.

Set `isDefaultForKotlinType = true` if the codec should also be chosen when the driver only knows the Kotlin class of a parameter and not its target OID. For a sealed class, that also covers every class below it, through sealed classes all the way down.

Registered codecs are remembered and re-bound on every catalog reload, so a codec bound by type name alone can be registered before its type exists and starts working after the next `reloadTypes()`. One that names a schema as well cannot: the type has to be there when it is registered.

### Overriding a built-in

Registration is last-wins in both layers, so overriding the defaults means registering your own on top:

* **Codec** — register a codec with the same OID (e.g. `1700` for `numeric`) to replace the built-in binary handling.
* **Converter** — register a `ResultConverter` that claims the shape you want to intercept; being newest, it is consulted before the built-in one. This is the natural place to turn every `PgInterval` into a `Duration`, or every `jsonb` into your own DTO.

## Practical rules and gotchas

* **Register once, at startup.** Registration is global and permanent for the JVM; doing it per request grows the converter lists and buys nothing.
* **`reloadTypes()` after runtime DDL** — including `CREATE TABLE`, whose row type is a composite. Without it, new types resolve to nothing.
* **Register before running the query, not before building it.** A query pins the catalog when a terminal runs, so a `registerCodec(...)` or a converter registration between `createNativeQuery(...)` and the `fetch*` call is picked up. What it will not do is reach a terminal already in flight, or rows a previous terminal returned.
* **Same database, different credentials, same catalog.** The cache key is the servers and the database only.
* **A type off the search path needs a schema.** A name the path does not reach is refused, naming the schemas that have it, rather than taken from whichever schema happens to define it.
* **Empty collections need `PgTyped`.** Erasure leaves nothing in an `emptyList()` for the driver to infer an element type from — the same goes for a list of nothing but nulls.
* **Not every PostgreSQL type has a codec.** `money`, `timetz`, `tsvector`, `tsquery`, `jsonpath` and friends throw `TypeException(MISSING_CODEC)`. Cast them in SQL or write the codec.
* **`MappingException.path` points at the failure.** For nested composites and arrays, read the path before reading the message.
* **Thousands of dynamic database URLs?** Call `GlobalCatalogStore.removeCatalog(url)` when tearing down a data source. Otherwise, ignore it.
