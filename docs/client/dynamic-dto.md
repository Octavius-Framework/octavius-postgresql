# `dynamic_dto`

*A titulus was carried in front of the thing it named — the placard borne ahead of a captive in a triumph, hung
on a statue, nailed above a condemned man. What was on it was not a description of the class of object. It said
what **this one** was, and it travelled with it.*

One column holding whichever of several unrelated shapes a row happens to carry. A `COMPOSITE` fixes the shape
at schema level; this fixes it per row, and the discriminator is stored in the data beside the payload.

## The Case a Composite Cannot Cover

```sql
CREATE TABLE veterans (
    id      SERIAL PRIMARY KEY,
    name    TEXT NOT NULL,
    benefit public.dynamic_dto      -- a land grant, a pension, a citation…
);
```

```kotlin
sealed interface Benefit

@Serializable data class LandGrant(val province: String, val iugera: Int) : Benefit
@Serializable data class MilitaryPension(val legion: String, val annual: Int) : Benefit

db.dynamicTypes.register<LandGrant>("land_grant")
db.dynamicTypes.register<MilitaryPension>("military_pension")

db.insertInto("veterans").values(listOf("name", "benefit"))
    .executeUpdate("name" to "Marcus", "benefit" to LandGrant("Gallia Narbonensis", 120))

val benefits: List<Benefit> = db.select("benefit").from("veterans").fetchFields()
```

The column comes back as whatever supertype you ask for, each row decoded as the class its own `type_name`
names. A composite's shape is decided once, in the schema, for every row.

Where the shape *is* fixed, use a composite: no JSON to encode, no discriminator to keep honest, no document for the
server to walk into before it can compare a field, and [ahead](performance.md#a-composite-or-a-dynamic_dto) writing,
reading and filtering. Where nothing is stored — an object built in the projection, a parent read with its
children — a [`ROW(...)` of keys and values](../driver/composites-reflection.md#pgrecord--anonymous-row) reads into
the class asked for with nothing registered, every field keeping its column's type. A `dynamic_dto` built in a query
is for [a class chosen per row](#a-class-chosen-by-the-query).

## Where It Goes

A column is one place. There are three more, and none of them needs anything the column did not.

### Several shapes in one array

`benefits public.dynamic_dto[]` holds a grant and a pension side by side:

```kotlin
val benefits = listOf(LandGrant("Gallia Narbonensis", 120), MilitaryPension("X Gemina", 300))

db.insertInto("veterans").values(listOf("name", "benefits"))
    .executeUpdate("name" to "Marcus", "benefits" to benefits)

val back: List<Benefit> = db.select("benefits").from("veterans").where("name = @n")
    .fetchFieldStrict("n" to "Marcus")
```

The list needs no wrapping, and comes back as the supertype asked for, or as `List<Any>` where the shapes have
nothing in common.

### A class chosen by the query

The value does not have to be stored at all. Built in the projection, each row names its own class, so rows from
tables that have nothing in common read as one list — here each veteran's benefits, from two tables:

```kotlin
data class Veteran(val id: Int, val name: String, val benefits: List<Benefit>)

val veterans = db.rawQuery(
    """
    SELECT v.id, v.name,
           ARRAY(
               SELECT dynamic_dto('land_grant', jsonb_build_object('province', g.province, 'iugera', g.iugera))
               FROM grants g WHERE g.veteran_id = v.id
               UNION ALL
               SELECT dynamic_dto('military_pension', jsonb_build_object('legion', p.legion, 'annual', p.annual))
               FROM pensions p WHERE p.veteran_id = v.id
           ) AS benefits
    FROM veterans v
    """
).fetchObjects<Veteran>()
```

The keys `jsonb_build_object` is given are the property names in `snake_case`, as the driver names columns; see
[Payload Keys](#payload-keys).

### Plain columns, the type only in transit

Storing the composite ties the table to it: anything else reading the table meets a `dynamic_dto` it has to take
apart. Where the table should stay plain — read by a report, a script, a service with no Octavius in it — the
discriminator and the payload live in columns of their own, and the type exists only on the way in and out:

```sql
CREATE TABLE archives (
    id          SERIAL PRIMARY KEY,
    record_type TEXT  NOT NULL,
    payload     JSONB NOT NULL
);
```

```kotlin
data class Archived(val id: Int, val record: Benefit)

// In: each value arrives as a dynamic_dto and is taken apart in SQL
db.rawQuery("INSERT INTO archives (record_type, payload) SELECT type_name, data_payload FROM UNNEST(@records)")
    .executeUpdate("records" to benefits)

// Out: put back together in the projection
val archived = db.rawQuery("SELECT id, dynamic_dto(record_type, payload) AS record FROM archives")
    .fetchObjects<Archived>()
```

A single value goes in as `SELECT (@r).type_name, (@r).data_payload`. Neither direction touches the document, since
the discriminator sits beside the payload rather than inside it. The price is that every statement touching the
table spells the conversion out itself.

## Creating the Type

The type is **not** created behind your back:

```sql
CREATE TYPE public.dynamic_dto AS (type_name text, data_payload jsonb);
```

`DYNAMIC_DTO_DDL` is that, plus the `dynamic_dto(text, jsonb)` constructor the SQL above calls, which PostgreSQL does
not create for a composite, and two `to_dynamic_dto` overloads; running it twice is harmless. Put it in a migration,
or call `install()` where a migration would be ceremony:

```kotlin
db.dynamicTypes.install()
```

`install()` also reloads the driver's type catalogue. The catalogue is read once per database, on the first
connection, so a type created after that is unknown to every session until a reload, and a column of it comes back
as an unknown OID. A type that existed before anything connected needs no reload.

## Registering a Class

```kotlin
db.dynamicTypes.register<LandGrant>("land_grant")     // states the name
db.dynamicTypes.register<LandGrant>()                 // reads @DynamicallyMappable
db.dynamicTypes.register(kClass, "land_grant")        // for a scanner that found the class
```

The class has to be `@Serializable`. The name is stated rather than derived from the class, because it is stored in
every row and a rename must not orphan them; stated at the call, it wins over `@DynamicallyMappable`.

Registration is **global to the database**, so it belongs at startup. Every client on the database reads the same
names, and a class is read and written with the `Json` of the client that registered it, whichever client a query
runs through. Two names for one class and one name for two classes are refused; the same class under the same name
again changes nothing, its `Json` included.

## Payload Keys

A payload is keyed the way the driver names a composite's attributes: each property under its name in `snake_case`,
or under its `@PgName`.

```kotlin
@Serializable
data class Stipend(@PgName("prov") val provinceName: String, val annualAmount: Int)
// {"prov": "Aegyptus", "annual_amount": 500}
```

A payload built in SQL therefore uses the names the schema already has, whether written out with
`jsonb_build_object` or made from a whole row by `to_dynamic_dto`:

```kotlin
val stipends = db.rawQuery("SELECT to_dynamic_dto('stipend', s) FROM stipends s").fetchFields<Stipend>()
```

A `bytea`, an `interval` and a range are the exceptions. `to_jsonb` writes them in PostgreSQL's text form —
`"\\xdead"`, `"1 day 02:00:00"`, `"[1,5)"` — and nothing in `octaviusSerializersModule` turns that text into a
`ByteArray`, a `PgInterval` or a `Range`. Such a column stays out of the payload, or the object is built as a
[`ROW(...)`](../driver/composites-reflection.md#pgrecord--anonymous-row), whose fields keep their types.

A `@SerialName` is converted like the property's own name, so `@PgName` is the way to give a key as it is. These are
the keys of `octaviusSnakeCaseJson`, the client's default; a payload keyed by the Kotlin names, as a stock `Json`
writes it, reads under `octaviusJson`, given to the client or [to one query](#a-different-json-for-one-query).

## Reading

| Ask for              | Get                                                     |
|----------------------|---------------------------------------------------------|
| the registered class | that class                                              |
| a supertype          | whichever registered class the row's `type_name` names  |
| `Any`                | the same                                                |
| `DynamicDto`         | the raw form: the discriminator and the payload as text |
| `Map<String, Any?>`  | the composite's own two attributes                      |

A name nothing is registered under, and a payload that does not fit the class, are both `MappingException`s naming
what they found rather than half-filling an object. `DynamicDto.dataPayload` is the JSON as text;
`Json.parseToJsonElement` makes a tree of it.

## Writing

An instance of a registered class is written as a `dynamic_dto` **as it is**, with nothing wrapped around it. Where
the value goes into something that declares its type — an attribute of a composite, an element of an array, a value
wrapped in `withPgType` — **that type decides**:

```kotlin
data class Decoration(val award: Honour, val citation: Triumph)   // public.decoration
// award    is declared public.honour       → written as the composite
// citation is declared public.dynamic_dto  → written as a dynamic_dto
```

A value that does not belong in the declared destination is a `MappingException(NO_CONVERTER_FOUND)` naming both.

A `DynamicDto` read earlier goes back as it is, its name not checked against the registry. Nothing but a read
makes one.

### A class registered as a composite too

Such a class can go in as either, so where nothing declares which — a parameter on its own, or the first element of
a list whose array type it would decide — it is refused with a `MappingException(CONVERSION_ERROR)` naming both
registrations. That holds whichever column the value is going into: the driver states each parameter's type before
the server has looked at the statement. `withPgType` names the one meant:

```kotlin
db.insertInto("veterans").values(listOf("name", "honour", "benefit"))
    .executeUpdate(
        "name" to "Marcus",
        "honour" to honour.withPgType("honour", "public"),
        "benefit" to honour.withPgType("dynamic_dto", "public")
    )
```

A list of it is named once, with `isArray = true`.

## What JSON Does Not Carry

The payload is [JSON](../driver/arrays-ranges-json.md#json-and-jsonb), and a few values the driver maps correctly in a
column of their own come out differently under the default serializer:

| Type              | What the default serializer writes    | What that costs                                                   |
|:------------------|:--------------------------------------|:------------------------------------------------------------------|
| `BigDecimal`      | nothing — it has no serializer        | the class does not encode at all                                  |
| `LocalDate`       | `+999999999-12-31` for `infinity`     | past what a `date` holds, so reading it back raises               |
| `LocalDateTime`   | `+999999999-12-31T23:59:59.999999999` | the same, a `timestamp` stopping at 294276 AD                     |
| `Instant`         | `+100000-01-01T00:00:00Z`             | a year a `timestamptz` does hold — so it comes back quietly wrong |
| `LocalTime`       | `23:59:59.999999999` for `24:00:00`   | the `24:00:00` that `to_jsonb` writes does not read back at all   |
| any date at all   | ISO-8601                              | outside years `0001`..`9999`, PostgreSQL will not read it back    |
| a registered enum | the Kotlin constant's own name        | `Praetor` in the payload where the enum column holds `PRAETOR`    |

Each is answered by one annotation on the property:

```kotlin
@PgEnumType                               // labels are SNAKE_CASE_UPPER in the database
enum class Magistrature { Quaestor, Aedile, Praetor, Consul }

@Serializable
@DynamicallyMappable("land_grant")
data class LandGrant(
    val province: String,
    @Contextual val iugera: BigDecimal,      // a bare JSON number, every digit kept
    @Contextual val until: LocalDate,        // "infinity"
    @Contextual val awardedBy: Magistrature  // "PRAETOR", as the enum column holds it
)
```

Without `@Contextual` a property keeps the default serializer. The first five come from `octaviusSerializersModule`,
which the client's `dynamicJson` carries; they write PostgreSQL's spelling and read either spelling back, so a payload
built in SQL still decodes. The enum's labels are the ones it was **registered** under, read off the driver's
registry, so `typeManager.registerEnum` and a scan by `@PgEnumType` both cover it, and the enum needs no
`@Serializable` of its own. An enum the driver has never been told about keeps the default, and one registered
between two queries applies to the second.

`BigDecimal` is `io.github.octaviusframework.type.BigDecimal` from `pg-model`, on the JVM a `typealias` for
`java.math.BigDecimal`; see [`BigDecimal` in a Shared Class](../driver/type-system.md#bigdecimal-in-a-shared-class).

A `Json` you build yourself — for an HTTP layer, or for a frontend reading the same classes — needs both modules,
since they are the client's default and not a global:

```kotlin
val json = Json {
    serializersModule = octaviusSerializersModule + db.dynamicTypes.enumSerializers + myAppModule
    ignoreUnknownKeys = true
}
```

`octaviusJson` is the first of those on a stock `Json`. `enumSerializers` answers for the enums registered when it
is read, so build that `Json` after startup registration.

The frontend has no registry, so there the enum's labels are written out once, with `registerEnum`'s defaults, and
the class is shared:

```kotlin
// commonMain, next to the enum itself
@Serializable(with = MagistratureSerializer::class)
@PgEnumType
enum class Magistrature { Quaestor, Aedile, Praetor, Consul }

object MagistratureSerializer : EnumWithCaseConventionSerializer<Magistrature>(
    enumName = "Magistrature",
    entries = Magistrature.entries
)
```

`@Serializable(with = …)` binds tighter than a contextual module, so the backend uses this one too and both ends
agree.

## A Different `Json` for One Query

The client's `Json` is `octaviusSnakeCaseJson`: strict, so a payload carrying a field the class does not declare is an
error rather than something dropped. A query reading payloads that were written some other way hands over a `Json` of
its own. A frontend posting its documents through `octaviusJson` keys them by the Kotlin names, and a `jsonb` column
that keeps them as they came holds those keys:

```kotlin
// stipend_requests.body: each request as the frontend posted it
val requests = db.rawQuery("SELECT dynamic_dto('stipend', body) FROM stipend_requests")
    .registerResultConverter(db.dynamicTypes.resultConverter(octaviusJson))
    .fetchFields<Stipend>()
```

Payloads that still carry a property the class has since lost read the same way, under
`Json(octaviusSnakeCaseJson) { ignoreUnknownKeys = true }`.

`parameterConverter(json)` is the write-side mirror, registered with `registerParameterConverter`. Query registries
sit ahead of the session's and are discarded with the query; see
[Per-Query Converters](queries.md#per-query-converters). A `Json` given here replaces the one the class was registered
with (`dynamicJson` on `fromDataSource` or `fromSessionProvider`), so build it on `octaviusJson` or
`octaviusSnakeCaseJson`, which carry `octaviusSerializersModule`, where the class has a `@Contextual` property.

## Next

- [Annotation Scanning](scanner.md) — registering these by annotation instead of by hand
- [Queries](queries.md) — the per-query converter mechanism this is built on
