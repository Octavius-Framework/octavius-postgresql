# Composites and Reflective Mapping

*A Roman muster took the roll by name. The clerk called, the man answered, and where he happened to be standing in the
line changed nothing. What the clerk could not do was work out from the answers which legion he was standing in front
of — that had to be written at the head of the tablet before the first name was read. Reflective mapping here works the
same way: attributes and properties find each other by name, in any order, and the one thing the driver cannot infer is
which PostgreSQL type your class is meant to be.*

[Type System](type-system.md) explains the machinery — which converter is chosen, what the registry does, how a value
travels. This page asks what the call site looks like, the same question
[Arrays, Ranges and JSON](arrays-ranges-json.md) asks of its own three families: what reflective mapping reads off the
class you hand it, where a composite differs from a row, and what to write when reflection is not the mapping you want.

Every example here is exercised against PostgreSQL 18, including the ones that fail.

Contents:
* [Two reflective mappers, one asymmetry](#two-reflective-mappers-one-asymmetry)
* [Rows onto data classes](#rows-onto-data-classes)
* [Composites onto data classes](#composites-onto-data-classes)
* [Writing a whole object as one parameter](#writing-a-whole-object-as-one-parameter)
* [What reflection reads, and what it ignores](#what-reflection-reads-and-what-it-ignores)
* [When a value is missing](#when-a-value-is-missing)
* [Reading them all as maps](#reading-them-all-as-maps)
* [Writing the converters by hand](#writing-the-converters-by-hand)
* [The raw forms: `PgComposite` and `PgRecord`](#the-raw-forms-pgcomposite-and-pgrecord)
* [Maps in and out: `toDataObject` and `toDataMap`](#maps-in-and-out-todataobject-and-todatamap)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## Two reflective mappers, one asymmetry

Two converters do reflective work, they look alike, and they do **not** have the same prerequisites:

| Source                       | Converter                      | Needs registration | Reached by                      |
|:-----------------------------|:-------------------------------|:-------------------|:--------------------------------|
| a whole result `Row`         | `ReflectionRowConverter`       | **no**             | `fetchObjects<T>()` and its kin |
| a `PgComposite` in one value | `ReflectionCompositeConverter` | **yes**            | `row.get<T>(...)` on that value |

A row can be mapped onto any data class the moment you ask for it. A composite value cannot, until the class has been
through `registerAutoComposite`. The distinction is not arbitrary — a registration is what carries the PostgreSQL
*type name* for a class, which both the write direction and the `row.get<Any>` direction need — but it is the first
thing to trip over:

```kotlin
data class Address(val city: String, val street: String)

// Fine, with nothing registered
session.createNativeQuery("SELECT city, street FROM senators").fetchObjects<Address>()

// MappingException(NO_CONVERTER_FOUND)
//   "No converter found for source class PgComposite and expected type Address"
session.createNativeQuery("SELECT residence FROM senators").fetchRowStrict().get<Address>("residence")
```

## Rows onto data classes

Ask for the class and the row is built from the column names:

```kotlin
data class Senator(
    val id: Int,
    val cognomen: String,
    val provinceId: Int?          // -> province_id
)

val senators: List<Senator> = session
    .createNativeQuery("SELECT id, cognomen, province_id FROM senators")
    .fetchObjects()
```

Property names are converted `camelCase` → `snake_case` to find their column, `@PgName` overrides an individual one, and
column order is irrelevant. Nothing has to be registered, and the class needs no annotation, interface or superclass.

## Composites onto data classes

```kotlin
data class Address(val city: String, val street: String)

data class SenatorProfile(
    val fullName: String,                     // -> full_name
    @PgName("cognomen_officiale")
    val cognomen: String,                     // -> cognomen_officiale
    val residence: Address?                   // nested composite, mapped recursively
)

session.typeManager.registerAutoComposite<Address>()
session.typeManager.registerAutoComposite<SenatorProfile>("senator_profile", schema = "imperium")
```

The type name defaults to the class name in `snake_case`, `schema` pins it, and registration is **bidirectional** — the
class is built from a `PgComposite` on the way in and turned back into one on the way out. Nothing is resolved against
the catalog at registration time, so the order of `registerAutoComposite` and `reloadTypes()` does not matter; the type
only has to exist by the time a query uses it. Registration is global for the database, so do it once at startup — see
[Scope](type-system.md#scope-a-session-handle-over-global-state).

### What you can ask a composite column for

Registration changes what a composite value can become, but it is not the only way to read one:

| `row.get<...>("residence")` | Registered     | Not registered       |
|:----------------------------|:---------------|:---------------------|
| `Address`                   | `Address`      | `NO_CONVERTER_FOUND` |
| `Map<String, Any?>`         | the attributes | the attributes       |
| `PgComposite`               | the raw value  | the raw value        |
| `Any`                       | `Address`      | `PgComposite`        |

So a composite is never unreadable — worst case you get a `Map` or the raw `PgComposite` and map it yourself.
`registerAutoComposite` is what buys you the class, in both directions, plus the `Any` row: that last one is how a
composite nested inside a `Map<String, Any?>` or a `ROW(...)` comes out as your own type rather than a container. A
hand-written converter can cover every row of this table too — see
[Claim `Any` as well as the class](#claim-any-as-well-as-the-class).

The table is what one column answers. To move the whole column — and everything under it — to the `Map` row for one
query, without touching what the class is registered as anywhere else, see
[Reading them all as maps](#reading-them-all-as-maps).

### Registration is per class, not per graph

Every data class that appears anywhere in the structure needs its own registration, including ones that only ever appear
as an attribute of another:

```kotlin
data class Tribute(val amount: Int, val currency: String)
data class Assessment(val label: String, val payload: Tribute)

session.typeManager.registerAutoComposite<Assessment>()
// Tribute NOT registered

// Reading:  MappingException(NO_CONVERTER_FOUND), path = payload
// Writing:  MappingException(NO_CONVERTER_FOUND), path = payload
```

Both directions report it the same way, and the `path` names the offending attribute — the fastest way to find the
class you forgot. A class registered nowhere at all and passed at top level has no attribute to name, so it says so
directly instead: `TypeException(MISSING_CODEC)` — *"Codec not found for: Tribute"*.

Registering two classes under one type name is not rejected, and the second one silently takes the name over: the
class → name direction stays keyed by class, so `row.get<Address>` keeps working, while `row.get<Any>` starts returning
the newcomer. Since the registry is shared per database, that lands on every session in the JVM. One class per type is
the only arrangement that behaves predictably.

## Writing a whole object as one parameter

A registered data class *is* a composite value, so it can go into an `INSERT` as a single parameter, be expanded
server-side with `(...).*`, and come back through `RETURNING *` as the same class with the generated key filled in:

```kotlin
data class Senator(val id: Int, val cognomen: String, val tags: List<String>)

session.typeManager.registerAutoComposite<Senator>("senators")   // the table's own row type

val saved: Senator = session.createNamedQuery(
    "INSERT INTO senators OVERRIDING USER VALUE SELECT (@row).* RETURNING *"
).fetchObjectStrict("row" to Senator(0, "Cicero", listOf("consul")))
// Senator(id = 1, cognomen = Cicero, tags = [consul])
```

One round trip, one parameter, no column-by-column plumbing, and the object handed back is the row that was written.

`OVERRIDING USER VALUE` is what makes the key column ignore whatever the object carried and take its sequence value
instead. The `0` above is a placeholder and nothing more — `9999` is discarded just the same. Coming back, `RETURNING *`
is read by the ordinary reflective row mapper, which needs no registration of its own; the registration is what the
*parameter* side uses.

### Nested values take their OID from the catalog

Tidiness is the obvious half. The half worth the section is that an **empty collection inside the object needs no
`withPgType`**:

```kotlin
// Column by column: MappingException(CONVERSION_ERROR), caused by TypeException(TYPE_NOT_FOUND)
session.createNamedQuery("INSERT INTO senators (cognomen, tags) VALUES (@c, @t)")
    .update("c" to "Brutus", "t" to emptyList<String>())

// As a whole object: fine, and the array reads back as []
session.createNamedQuery("INSERT INTO senators OVERRIDING USER VALUE SELECT (@row).* RETURNING *")
    .fetchObjectStrict<Senator>("row" to Senator(0, "Brutus", emptyList()))
```

A bare `emptyList()` has to have its element type guessed from a first element that is not there, which is why it
fails — see [Empty collections need their type stated](arrays-ranges-json.md#empty-collections-need-their-type-stated).
Inside a composite there is nothing to guess: the attribute's OID comes from the enclosing type's catalog definition, so
the inference never runs. That holds for every nested value, and it is
[the by-OID branch](type-system.md#one-default-per-kotlin-class) of parameter serialization doing the work.

### The key column decides which form you can use

`SELECT (@row).*` sends every attribute, key included, so the key has to be neutralized server-side — which is what
`OVERRIDING USER VALUE` does, and it works for identity columns **only**. Against a `serial` key it is silently a no-op:
the placeholder is stored as written, the first call succeeds and reports `id = 0`, and the second fails with
`ConstraintViolationException(UNIQUE_CONSTRAINT_VIOLATION)` naming `(id)=(0)` — one row later than its cause.

Naming the fields leaves the key out of the statement instead, which works whatever kind of key it is:

```kotlin
session.createNamedQuery(
    "INSERT INTO senators (cognomen, tags) SELECT (@row).cognomen, (@row).tags RETURNING *"
).fetchObjectStrict<Senator>("row" to Senator(0, "Sulla", listOf("dictator")))
// Senator(id = 2, cognomen = Sulla, tags = [dictator])
```

`VALUES` does the same with `DEFAULT` standing in for the key and no column list —
`INSERT INTO senators VALUES (DEFAULT, (@row).cognomen, (@row).tags) RETURNING *`. `DEFAULT` is the uniform answer for a
key: it works for `serial` and for both flavours of `GENERATED … AS IDENTITY`, and it is the only value a
`GENERATED ALWAYS AS (…) STORED` column accepts, so a computed column written as `DEFAULT` is calculated normally
instead of rejecting the statement.

Both cost a field list in the SQL and give up nothing else. The object still travels as **one** composite parameter, so a
nested empty collection still takes its OID from the catalog, and `RETURNING *` still hands the whole entity back.

What does not work is combining the two. `VALUES (DEFAULT, (@row).*)` against a key-carrying composite is
`StatementException(SYNTAX_ERROR)` — *"INSERT has more expressions than target columns"* — because `.*` already expanded
the key. So: `.*` with no field list is what `OVERRIDING USER VALUE` exists for, and everything else names its fields.

### Upsert, and what it costs

`ON CONFLICT` turns the same statement into save-or-update, and returns the row either way:

```kotlin
session.createNamedQuery(
    """
    INSERT INTO senators SELECT (@row).*
    ON CONFLICT (id) DO UPDATE SET cognomen = EXCLUDED.cognomen, tags = EXCLUDED.tags
    RETURNING *
    """
).fetchObjectStrict<Senator>("row" to existing)
```

Note what is missing from it: `OVERRIDING USER VALUE`. The two do not compose. With it in place the key is replaced
before the conflict is ever evaluated, so every call inserts a new row and the update branch never runs — and it does
that silently rather than complaining. One statement can generate a key or conflict on one you supplied, not both; an
entity that may be either new or existing needs those two paths kept apart.

### Without the key in the class

Where the object is an *insert shape* rather than an entity — no key at all — nothing has to be overridden:

```kotlin
data class NewSenator(val cognomen: String, val tags: List<String>)

session.createNamedQuery("INSERT INTO senators (cognomen, tags) SELECT (@row).* RETURNING id")
    .fetchField<Int>("row" to NewSenator("Cato", listOf("censor")))
```

This is the one shape where `.*` and `DEFAULT` do combine, since there is no key in the composite to collide with the
placeholder: `INSERT INTO senators VALUES (DEFAULT, (@row).*)` drops the column list as well. Reach for `DEFAULT` per
column anywhere the table's own default should win over what the object holds.

The trade is a second type to keep in step with the table — though that type *is* the insert contract, which is an
argument for it as much as against.

> [!NOTE]
> **The `VALUES` form is positional; the column-list form is not.** `VALUES (DEFAULT, (@row).*)` assumes the key is the
> first column and that the composite's fields line up with the ones after it. Adding a column is survivable —
> PostgreSQL appends, and a `VALUES` list shorter than the table gives the remaining columns their defaults — but
> reordering or dropping one shifts the mapping with nothing to warn you. `INSERT INTO senators (cognomen, tags)
> SELECT (@row).*` names what it writes and is unaffected by any of that, including a later `ADD COLUMN … NOT NULL
> DEFAULT`, which simply takes its default. Prefer it unless you have a reason not to.

## What reflection reads, and what it ignores

Both reflective mappers use the same metadata, computed once per class and cached for the lifetime of the JVM:

* **The primary constructor is the whole contract.** Only its parameters are read. A property declared in the class body
  is invisible in both directions — it is never populated from the database and never written to it.
* **A data class is required.** Reflective mapping reads every primary constructor parameter back as a property, which
  is exactly what a data class guarantees. `registerAutoComposite` rejects anything else with
  `InvalidOperationException(INVALID_ARGUMENT)` at registration time rather than at query time, and both reflective
  converters only answer for data classes. Any other shape wants a converter pair of its own.
* **Names are matched, positions are not.** Column and attribute order never matters.
* **Extra columns and attributes are ignored.** A `SELECT *` that returns more than your class declares is fine.
* **Extra properties are dropped on the way out.** Writing iterates the *type's* attributes, so a property with no
  matching attribute is not sent, and an attribute with no matching property is written as `NULL`:

```kotlin
data class Address(val city: String, val province: String)   // no "street"
// against CREATE TYPE address AS (city text, street text)

// sent as ROW('Roma', NULL) - "province" is dropped, "street" has nothing to fill it
```

Reflection costs something. [Performance](performance.md#reflection-or-a-hand-written-converter) has the measured
figures against a hand-written converter, and [Writing the converters by hand](#writing-the-converters-by-hand) is what
to do about it.

## When a value is missing

Two things can leave a property with nothing to hold, and they are not the same thing: the column or attribute is **not
there at all**, or it is there and holds **SQL `NULL`**. One rule separates them:

> A default value replaces an *absent* column or attribute. It never replaces a value, and `NULL` is a value.

Which gives the whole matrix:

| Property                            | Column/attribute absent      | Present, value is `NULL`     |
|:------------------------------------|:-----------------------------|:-----------------------------|
| `val province: String`              | `REQUIRED_ATTRIBUTE_MISSING` | `REQUIRED_ATTRIBUTE_MISSING` |
| `val province: String?`             | `null`                       | `null`                       |
| `val province: String = "unknown"`  | `"unknown"`                  | `REQUIRED_ATTRIBUTE_MISSING` |
| `val province: String? = "unknown"` | `"unknown"`                  | `null`                       |

The bottom two cells on the right are the point of the rule. A `SELECT` that returned the column and found `NULL` has
told you something, and a default is not allowed to overwrite it — so a non-nullable property is a mapping failure, and
a nullable one is `null` even though a default exists. Nullability is what says "`NULL` is acceptable here"; a default
is what says "this column may not be in the result set at all". They answer different questions, and a property can
legitimately carry both.

The exception carries the property in its `path`, so a failure five levels down in a nested composite names the field
rather than the root — read [`MappingException.path`](exceptions.md) before reading the message.

## Reading them all as maps

Registration is what a composite **is** to every session pointing at that database, so it is not where one report
saying *"give me the shape, not my classes"* can be answered. `compositesAsMaps()`, from
`io.github.octaviusframework.driver.converter.result.composite`, is: a converter registered on a query, applying to
that query and discarded with it.

```kotlin
session.createNativeQuery("SELECT * FROM censuses")
    .registerResultConverter(compositesAsMaps())
    .fetchObjects<Map<String, Any?>>()

// {id=1, assessment={label=census, payload={amount=40, currency=denarius}}}
```

It collapses the **whole subtree**, and gets that from the value type rather than by walking one: attributes are
converted as `Any?`, a nested composite asked for as `Any?` reaches the same converter again, and an array of
composites hands its elements down the same way. A `ROW(...)`
[already answers to a map](#the-raw-forms-pgcomposite-and-pgrecord) on its own.

A composite registered nowhere collapses along with the rest, which is what stops one turning up as a raw
`PgComposite` three levels down — the one row of [the table above](#what-you-can-ask-a-composite-column-for) that
asking for a `Map<String, Any?>` does not reach on its own.

**Naming a class still gets the class.** Only `Any` and `Map` are claimed — *no preference* and *a dictionary* — so an
explicit ask in the same query is untouched:

```kotlin
val row = session.createNativeQuery("SELECT assessment FROM censuses")
    .registerResultConverter(compositesAsMaps())
    .fetchRowStrict()

row.get<Map<String, Any?>>("assessment")   // {label=census, payload={amount=40, …}}
row.get<Assessment>("assessment")          // Assessment(label=census, payload=Tribute(…))
```

What changes is what a composite is *by default*, not what naming a class means. Which is also why a data class is
unaffected below its own surface: `Assessment` declares `payload: Tribute`, and a declared property type is an
explicit ask like any other.

The one-type form names the composite, and every other one goes on mapping as it did:

```kotlin
val row = session.createNativeQuery("SELECT assessment, tribute FROM censuses")
    .registerResultConverter(compositesAsMaps("tribute"))     // "tribute", "public" to pin the schema
    .fetchRowStrict()

row.get<Any>("assessment")   // Assessment — not the named type, so reflection still has it
row.get<Any>("tribute")      // a Map
```

Left without a schema the name matches wherever the value came from, which is the right reading when the type is
reached through the search path rather than written out.

For several types, name them all in one converter rather than registering one apiece:

```kotlin
.registerResultConverter(compositesAsMaps(listOf(QualifiedName("", "tribute"), QualifiedName("", "levy"))))
```

Every converter on a query is asked about every composite it decodes, and that one is a single converter however
many names it holds.

## Writing the converters by hand

Reach for a hand-written pair when reflection is not the mapping you want: a legacy column layout, a value class, a
shape assembled from several attributes — or a hot path where the reflective cost shows up in a profile.

```kotlin
data class Tribute(val amount: Int, val currency: String)

class TributeResultConverter : ResultConverter<PgComposite, Tribute> {
    override val supportedSourceClass = PgComposite::class

    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = expectedType.classifier == Tribute::class &&
                 sourceType is PgType.Composite && sourceType.name == "tribute"

    override fun convert(
        source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Tribute = Tribute(source.get("amount"), source.get("currency"))
}

class TributeParameterConverter : ParameterConverter<Tribute> {
    override val supportedClass = Tribute::class

    override fun convert(source: Tribute, expectedOid: Int, context: SerializationContext): Any {
        // Known when the value is nested in a composite or array, or was wrapped in PgTyped
        val composite = if (expectedOid.isKnownOid) {
            context.types.containers.createComposite(expectedOid)
        } else {
            context.types.containers.createComposite("tribute")
        }
        composite["amount"] = source.amount
        composite["currency"] = source.currency
        return composite
    }

    // Not consulted for the composite itself - see below
    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("", "tribute")
}

session.typeManager.registerResultConverter(TributeResultConverter())
session.typeManager.registerParameterConverter(TributeParameterConverter())
```

`ContainerFactory` (`types.containers` in a converter, `typeManager.containers` on a session) is the clean way to build one: `createComposite` by name or by OID.

That pair replaces `registerAutoComposite` — the class needs no registration and nothing else changes at the call site.
A `List<Tribute>` becomes a `tribute[]`, a `Tribute` nested inside another composite is converted through the same chain
in both directions, and the parameter needs no `withPgType` and no `::cast` in the SQL, because a `PgComposite` carries
its own OID and is therefore self-describing.

### Claim `Any` as well as the class

One thing the converter above does not do is answer for `row.get<Any>`, and that row of
[the table](#what-you-can-ask-a-composite-column-for) is worth keeping: it is what resolves a composite sitting inside a
`Map` or a `ROW(...)`. Reflection gets it from the type-name lookup `registerAutoComposite` fills; a hand-written
converter gets it by saying so:

```kotlin
override fun canConvert(
    sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
): Boolean {
    if (sourceType !is PgType.Composite || sourceType.name != "tribute") return false
    val requested = expectedType.classifier
    return requested == Tribute::class || requested == Any::class
}
```

The source half stays exactly as narrow as it was, so the `Any` clause only ever fires for `tribute` values:

```kotlin
row.get<Any>("payload")                          // Tribute
row.get<Map<String, Any?>>("assessment")         // {label=census, payload=Tribute(...)}
row.get<Any>("some_other_composite")             // still PgComposite - untouched
```

Order matters in that predicate. Testing `sourceType` first and returning early is what keeps the `Any::class` clause
from turning into the source-only leak described just above — a converter that answered `Any` for *every* composite
would displace the reflective one database-wide.

### `canConvert` has to narrow on both axes

A result converter is indexed by the class the codec produced — `PgComposite` for *every* composite in the database, not
just yours. Narrowing only on the expected Kotlin type leaves the converter offered every composite value there is, and
narrowing only on the source type claims every request against that PostgreSQL type. The `sourceType.name == "tribute"`
and `expectedType.classifier == Tribute::class` halves above are both load-bearing.
[Arrays, Ranges and JSON](arrays-ranges-json.md#your-own-dtos) works this failure through in detail for a JSON DTO; the
same two leaks apply here, and the target-only one is the dangerous half, because it returns exactly the class that was
asked for and nothing downstream can tell it was built from the wrong column.

### `getDefaultTypeName` is not for the composite

The comment in the example above is deliberate. `getDefaultTypeName` exists so a converter whose output is a plain
scalar can declare what PostgreSQL type it should go out as — that is how a registered enum reaches its type without a
cast. **It is skipped entirely when a converter returns a `PgContainer`**, and a composite converter always returns one.
Drop the override and the converter still works everywhere a composite goes: as a top-level parameter, nested in another
composite, or as an element of an array.

There is exactly one place it is still consulted for a composite, and it is not obvious: resolving the range type for a
`Range<T>` over your class.

```kotlin
// CREATE TYPE tribute_range AS RANGE (subtype = tribute)
val assessed = rangeOf(lowerBound = Tribute(1, "denarii"), upperBound = Tribute(9, "denarii"))

session.createNativeQuery("SELECT $1 AS r").fetchRowStrict(assessed)
```

Without the override that throws `MappingException(CONVERSION_ERROR)` caused by `TypeException(TYPE_NOT_FOUND)` —
*"Cannot infer range type. The range is empty or bounds are null."* The range converter has a `Range<Tribute>`, needs
the OID of `tribute` to find `tribute_range`, and asks the parameter converter for that class what type name it
declares. With the override, or with `registerAutoComposite` (which supplies its own), the same call succeeds. Pinning
the parameter works too, if you would rather not carry the override:

```kotlin
assessed.withPgType("tribute_range")
```

## The raw forms: `PgComposite` and `PgRecord`

Both are what the **codec** layer produces, one step below the converters: their fields hold exactly what the codec for
each attribute's OID decoded, and nothing there has been through a converter. A nested composite is another
`PgComposite`, an array is a `PgArray`, an enum is its label as a `String`. An attribute of a base type is already the
Kotlin value you want — the `Int` and the `String` the [hand-written converter](#writing-the-converters-by-hand) reads
straight off — and anything the converter layer would have mapped stays raw until something maps it: `context.convert`
with the attribute's OID, which is the call `ReflectionCompositeConverter` makes for every attribute.

That context is a converter's own argument. Outside one, read a raw form for what it says about **shape** —
`attributeNames`, `getAttributeOid`, a `PgRecord`'s positional `fields` — and read the values through `row.get<T>(...)`,
which has already run the whole chain, nested attributes included.

### `PgComposite`

Every composite decodes to a `PgComposite` before any converter sees it, and you can ask for that directly:

```kotlin
val raw: PgComposite = row.get("residence")

raw.attributeNames              // List<String>, in catalog order
raw.containerOid                // the OID of the composite type
raw.get<String>("city")         // one attribute, type-checked by name
raw.get<String>(0)              // or by index
raw.getAttributeOid("city")     // the OID of that attribute's type
raw["city"] = "Ostia"           // mutable - this is how a converter builds one
```

`get` is checked: asking an attribute for the wrong class throws `MappingException(CONVERSION_ERROR)` — *"Expected Int,
got String"* — rather than a `ClassCastException` further down. The setters are what make `ContainerFactory` plus
`PgComposite` a usable pair for hand-written parameter converters.

A table's row type is a composite like any other, so `SELECT t FROM senators t` hands back a `PgComposite` whose
attributes are the table's columns.

### `PgRecord` — anonymous `ROW(...)`

An anonymous record carries no attribute names on the wire, so it does not map onto a data class — `row.get<Tribute>`
against a `ROW(...)` value throws `NO_CONVERTER_FOUND`. What it is for is the shape it *does* have: an **ad-hoc keyed
structure**, assembled in SQL and read as a `Map`, with the fields taken as alternating key/value pairs.

```kotlin
session.createNativeQuery("SELECT ROW('status', 'active', 'province', 7) AS r")
    .fetchRowStrict().get<Map<String, Any?>>("r")        // {status=active, province=7}
```

Both halves of a pair go through the converter chain, against the type arguments of the `Map` asked for, so
`Map<Int, String>` reads a record keyed by `int4` and `Map<Tribute, Int>` one keyed by a registered composite. Asked for
as `Any`, or as a `Map` with nothing said about its arguments, each half is what the chain makes of it with nothing
narrower named — a registered composite still arriving as its class.

Values go through the same chain, so anything the driver can convert belongs on the right-hand side — a registered
composite, an enum, an array, or another `ROW(...)`:

```sql
SELECT ROW(
    'tags',    ARRAY['a','b'],
    'payload', ROW(10, 'denarii')::tribute,
    'inner',   ROW('depth', 2)
) AS r
-- {tags=[a, b], payload=Tribute(amount=10, currency=denarii), inner={depth=2}}
```

That is the shape to reach for when a query needs to return something keyed that no declared type covers.

Three shapes are refused with `MappingException(CONVERSION_ERROR)` rather than guessed at, each of them one that would
otherwise leave the map holding fewer fields than the record did: an odd number of fields — *"Record fields must be in
key-value pairs"* — a `NULL` key, and a key equal to one already read. A key that converts to none of what was asked for
fails where it is met instead, `NO_CONVERTER_FOUND` at the field's position, which is how an `int4` key under
`Map<String, Any?>` arrives.

The raw container is there when you want the positional view instead:

```kotlin
val record: PgRecord = row.get("r")
record.fields        // Array<Any?>, positional
record.fieldOids     // IntArray, the OID of each field
```

Uncast literals are fine on both sides. PostgreSQL types a bare `'active'` as `unknown`, which the driver loads from
the catalog like any other base type and decodes to `String`, so no `::text` is needed to make a `ROW(...)` convertible.

## Maps in and out: `toDataObject` and `toDataMap`

The same reflective matching is available directly, with no database in the picture. Both live in
`io.github.octaviusframework.driver.util.reflection`:

```kotlin
import io.github.octaviusframework.driver.util.reflection.toDataMap
import io.github.octaviusframework.driver.util.reflection.toDataObject

Address("Roma", "Via Sacra").toDataMap()              // {city=Roma, street=Via Sacra}
Address("Roma", "Via Sacra").toDataMap("street")      // {city=Roma} - excluded keys

mapOf("city" to "Roma", "street" to "Via Sacra").toDataObject<Address>()
```

Keys are the mapped names, so `@PgName` applies to both directions here too. `toDataObject` follows the same
missing-value rules as [the table above](#when-a-value-is-missing) and adds a runtime type check, reporting a mismatch
as `MappingException(CONVERSION_ERROR)` — *"Incompatible type. Expected kotlin.String but got class kotlin.Int"* — with
the offending key in `path`.

This is the practical bridge for a composite you have chosen not to register: read it as a `Map<String, Any?>` and call
`toDataObject<T>()`. It also works on any class with a primary constructor, data class or not.

## Practical rules and gotchas

* **Rows need no registration; composite values do.** `fetchObjects<T>()` maps any data class as it stands, but a
  `PgComposite` needs `registerAutoComposite` before it becomes one.
* **Register every class in the graph.** Registration is per class; a nested data class needs its own call, and the
  `path` on the failure names it.
* **Two classes under one type name collide silently**, and the loser is the `row.get<Any>` direction.
* **Data classes only, and only their primary constructor.** `registerAutoComposite` rejects anything else outright;
  properties declared in the class body are invisible in both directions.
* **A default replaces an absent column, never a value.** `NULL` is a value, so nullability — not a default — is what
  makes `NULL` acceptable. A property may carry both.
* **An unregistered composite is still readable** as `Map<String, Any?>` or `PgComposite` — pair the map with
  `toDataObject<T>()` when you want the class without the registration.
* **Passing a whole object as one composite parameter dodges the empty-collection problem.** A nested `emptyList()`
  takes its element OID from the attribute definition, so it needs no `withPgType`.
* **`OVERRIDING USER VALUE` only affects identity columns.** It is the price of `(@row).*` with a key-carrying class,
  and against a `serial` key it is silently a no-op whose collision surfaces a row later. Naming the fields instead —
  or `DEFAULT` in a `VALUES` list — works with any kind of key and keeps the single-parameter benefits.
* **A generated key and `ON CONFLICT` do not compose.** `OVERRIDING USER VALUE` replaces the key before the conflict is
  evaluated, so the update branch never runs and nothing says so.
* **A hand-written converter needs `canConvert` narrowed on both axes** — the Kotlin type asked for *and* the source
  PostgreSQL type.
* **A hand-written converter has to claim `Any::class` too** if you want `row.get<Any>` — and therefore composites
  nested inside a `Map` or a `ROW(...)` — to resolve to your class. Narrow on `sourceType` first.
* **`getDefaultTypeName` is skipped for container-returning converters.** Override it only for the `Range<T>` case, or
  pin the range with `withPgType`.
* **`ROW(...)` is a keyed structure, not a row.** No names on the wire and no data class mapping; read as a `Map` it is
  alternating key/value pairs.
