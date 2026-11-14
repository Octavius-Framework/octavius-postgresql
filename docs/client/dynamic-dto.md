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

The keys `jsonb_build_object` is given are the class's property names; a payload named the way SQL names things
reads under [a `Json` of its own](#a-different-json-for-one-query).

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
names, and a class is read and written on the `Json` and the write strategy of the client that registered it,
whichever client a query runs through. Two names for one class, one name for two classes, and the same class on
another strategy are refused; the same class under the same name again changes nothing, its `Json` included.

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

An instance of a registered class is written as a `dynamic_dto` **without being wrapped**. When that happens is
`DynamicWriteStrategy`, given to the client when it is built:

```kotlin
OctaviusClient.fromDataSource(dataSource, dynamicWriteStrategy = DynamicWriteStrategy.EXPLICIT_ONLY)
```

| Mode                         | A registered class, unwrapped                                                                            |
|------------------------------|----------------------------------------------------------------------------------------------------------|
| `AUTOMATIC_WHEN_UNAMBIGUOUS` | Written as a `dynamic_dto`, unless the class is a registered composite too — then the composite takes it |
| `PREFER_DYNAMIC_DTO`         | Written as a `dynamic_dto` either way                                                                    |
| `EXPLICIT_ONLY`              | Refused by name, pointing at the wrapper                                                                 |

`AUTOMATIC_WHEN_UNAMBIGUOUS` is the default, and under it a class registered one way only needs no wrapping anywhere.

**`toDynamicDto(value)` overrides all three**, and settles the one case they disagree about:

```kotlin
db.insertInto("veterans").values(listOf("name", "benefit"))
    .executeUpdate("name" to "Marcus", "benefit" to db.dynamicTypes.toDynamicDto(honour))
```

It is also the only way to make a `DynamicDto`, and it checks the name against the registry. The server knows nothing
of the registry and would store any name, leaving it to fail on whichever read reached it.

### What the modes do not reach

A mode decides only for a parameter whose type nothing declares. An attribute of a composite, an element of an array
and a value wrapped in `withPgType` carry the type they are going into, and **that type decides**, under every mode:

```kotlin
data class Decoration(val award: Honour, val citation: Triumph)   // public.decoration
// award    is declared public.honour       → written as the composite
// citation is declared public.dynamic_dto  → written as a dynamic_dto
```

Naming the type is therefore the counterpart of `toDynamicDto`, and how a class registered both ways reaches its
composite under `PREFER_DYNAMIC_DTO`:

```kotlin
db.insertInto("veterans").values(listOf("name", "honour"))
    .executeUpdate("name" to "Marcus", "honour" to honour.withPgType("honour", "public"))
```

A value that does not belong in the declared destination is a `MappingException(NO_CONVERTER_FOUND)` naming both.

## What JSON Does Not Carry

The payload is [JSON](../driver/arrays-ranges-json.md#json-and-jsonb), and a few values the driver maps correctly in a
column of their own come out differently under the default serializer:

| Type              | What the default serializer writes    | What that costs                                                   |
|:------------------|:--------------------------------------|:------------------------------------------------------------------|
| `BigDecimal`      | nothing — it has no serializer        | the class does not encode at all                                  |
| `LocalDate`       | `+999999999-12-31` for `infinity`     | past what a `date` holds, so reading it back raises               |
| `LocalDateTime`   | `+999999999-12-31T23:59:59.999999999` | the same, a `timestamp` stopping at 294276 AD                     |
| `Instant`         | `+100000-01-01T00:00:00Z`             | a year a `timestamptz` does hold — so it comes back quietly wrong |
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

Without `@Contextual` a property keeps the default serializer. The first four come from `octaviusSerializersModule`,
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

The client's `Json` is `octaviusJson`: strict, so a payload carrying a field the class does not declare is an error
rather than something dropped. A payload built with `jsonb_build_object` is named the way SQL names things, so for
that one query, hand over a different one:

```kotlin
val snakeCase = Json { namingStrategy = JsonNamingStrategy.SnakeCase }

db.rawQuery("SELECT dynamic_dto('stipend', jsonb_build_object('province_name', @p, 'annual_amount', @a))")
    .registerResultConverter(db.dynamicTypes.resultConverter(snakeCase))
    .fetchFieldStrict<Stipend>("p" to "Aegyptus", "a" to 500)
```

`parameterConverter(json)` is the write-side mirror, and `toDynamicDto(value, json)` takes one for a value wrapped by
hand. Query registries sit ahead of the session's and are discarded with the query; see
[Per-Query Converters](queries.md#per-query-converters). A `Json` given here replaces the one the class was registered
with (`dynamicJson` on `fromDataSource` or `fromSessionProvider`), so put `octaviusSerializersModule` on it too where
the class has a `@Contextual` property.

## Next

- [Annotation Scanning](scanner.md) — registering these by annotation instead of by hand
- [Queries](queries.md) — the per-query converter mechanism this is built on
