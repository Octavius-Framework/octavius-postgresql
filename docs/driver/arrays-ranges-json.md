# Arrays, Ranges and JSON

*Three records gave the Roman archivist more trouble than the rest: the bundle of tablets that had to keep its order,
the boundary stone that had to declare whether the line itself belonged to the field, and the letter whose contents no
register could have anticipated. Arrays, ranges and JSON are those same three, and this page is about what each of them
looks like at the call site.*

[Type System](type-system.md) explains the machinery that maps PostgreSQL types onto Kotlin ones — which converter is
chosen, how to replace it, what the registry does. This page asks the other question, for three families where the
machinery is not the interesting part: what does the call site look like.

Arrays and JSON are here because they map onto things you already know — `List`, `Set`, `JsonElement` — in ways with
enough corners (nullable elements, more than one dimension, empty collections, `json` against `jsonb`) to be worth
writing down. Ranges are here because they genuinely have no counterpart: `ClosedRange` cannot express an exclusive
bound, an infinite one, or an empty range, so the driver brings its own `Range<T>`.

Composites belong to the same everyday category and have a page of their own —
[Composites and Reflective Mapping](composites-reflection.md) — because the registration their story turns on brings the
reflective mapper with it. They appear on this page only where they sit inside one of the three: as array elements, as
range bounds, as the holder of a JSON attribute.

Every example here is exercised against PostgreSQL 18, including the ones that fail.

Contents:
* [Arrays](#arrays)
* [Ranges and multiranges](#ranges-and-multiranges)
* [JSON and JSONB](#json-and-jsonb)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## Arrays

### Reading

Ask for the collection you want and the array arrives as one:

```kotlin
val row = session.createNativeQuery("SELECT ARRAY[1, 2, 3] AS legions").fetchRowStrict()

val legions: List<Int> = row.get("legions")   // [1, 2, 3]
```

`List`, `Collection`, `Iterable` and `Set` are all accepted targets — `Set` deduplicates on the way, so an `int[]` of
`{1,2,2,3}` becomes a three-element set. `IntArray` and the other primitive arrays work too, through a separate
converter.

Element types follow the same rules as columns, so an array of anything the driver can decode works without extra
setup — including arrays of your own registered composites and enums, which come back as `List<Senator>` and
`List<LegioStatus>`.

### Nulls in the array

PostgreSQL arrays may contain `NULL`, and Kotlin's type system is what decides whether that is acceptable. State it in
the element type:

```kotlin
val row = session.createNativeQuery("SELECT ARRAY[1, NULL, 3] AS spotty").fetchRowStrict()

val ok: List<Int?> = row.get("spotty")    // [1, null, 3]
val bad: List<Int> = row.get("spotty")    // MappingException(REQUIRED_ATTRIBUTE_MISSING)
```

The failure is not a surprise on purpose — a `List<Int>` promising no nulls and holding one would push the problem into
your code and turn up somewhere less obvious. The exception's `path` names the offending index (`[1]`).

### Multidimensional arrays

PostgreSQL arrays can have more than one dimension, and nested Kotlin collections map onto them in both directions:

```kotlin
val grid: List<List<Int>> = session
    .createNativeQuery("SELECT ARRAY[[1,2,3],[4,5,6]]")
    .fetchRowStrict().get(0)                     // [[1, 2, 3], [4, 5, 6]]

val back: List<List<Int>> = session
    .createNativeQuery("SELECT $1")
    .fetchRowStrict(listOf(listOf(1, 2), listOf(3, 4))).get(0)
```

Note PostgreSQL's own rule underneath this: a multidimensional array is rectangular, so the inner lists must all be the
same length. Ragged nesting is not a shape the type can hold.

### Writing

A Kotlin collection becomes an array parameter with nothing declared:

```kotlin
session.createNativeQuery("INSERT INTO provinces (id, legion_ids) VALUES ($1, $2)")
    .update(7, listOf(9, 10, 13))
```

The element type is inferred from the first non-null element — `List<Int>` goes out as `int4[]`, `List<String>` as
`text[]`. Usually that is the end of it, including when the column is wider than what you sent: a `List<Int>` inserted
into a `bigint[]` column is widened by PostgreSQL's own assignment cast, with nothing needed on the Kotlin side.

Where you do have to intervene, there are two mechanisms and they are **not** interchangeable:

|                                        | Says                         | Where it acts                      |
|:---------------------------------------|:-----------------------------|:-----------------------------------|
| [`withPgType`](type-system.md#pgtyped) | "this is what I am sending"  | Client, before anything is written |
| A cast in SQL                          | "turn what I sent into this" | Server, on the value it received   |

That distinction has teeth, because the OID `withPgType` names is what selects the **codec that encodes your value**. It
cannot widen anything:

```kotlin
// MappingException(NO_CONVERTER_FOUND), path [0] - it asks the int8 codec to encode Int values
listOf(1, 2, 3).withPgType(PgStandardType.INT8_ARRAY)

// Fine - int4[] goes out, PostgreSQL widens it
session.createNativeQuery("SELECT $1::int8[]").fetchRowStrict(listOf(1, 2, 3))
```

So reach for `withPgType` when the driver cannot infer a type at all — an empty collection — or infers one the server
will not take, such as a `String` bound for a `jsonb` column. Reach for a SQL cast when what you sent has to become
something else on the server: an array compared whole, since `anyarray` takes both sides as one type and coerces
neither (`varchar[] = text[]` has no operator at all); a conversion PostgreSQL makes only when asked, such as `text`
to `jsonb`; an overload to disambiguate. [When a cast earns its
place](bulk-writes.md#when-a-cast-earns-its-place) draws the whole boundary.

### Empty collections need their type stated

An `emptyList()` has no first element to infer from, and erasure means there is nothing else to look at:

```kotlin
session.createNativeQuery("SELECT * FROM UNNEST($1)").fetchFields<Int>(emptyList<Int>())
// MappingException(CONVERSION_ERROR), caused by TypeException(TYPE_NOT_FOUND)
```

The `TypeException` is the one naming the problem; it arrives wrapped because the converter layer wraps everything that
fails under it. A list holding nothing but nulls fails the same way. The fix is to say what it is:

```kotlin
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.withPgType

session.createNativeQuery("SELECT * FROM UNNEST($1)")
    .fetchFields<Int>(emptyList<Int>().withPgType(PgStandardType.INT4_ARRAY))   // -> []
```

`PgStandardType` covers the built-in array types; `withPgType("legio_status", isArray = true)` names one of yours. If an
empty batch simply means "no work", guarding with `if (list.isEmpty()) return` is just as good.

### The raw form: `PgArray`

Every array decodes to a `PgArray` before any converter sees it, and you can ask for that directly when the collection
shape is not what you want:

```kotlin
val raw: PgArray = row.get("legions")

raw.elements        // flat List<Any?>, all dimensions concatenated
raw.dimensions      // List<ArrayDimension>(size, lowerBound) - empty for a 1-D array
raw.elementOid      // the OID of the element type
raw.get<Int>(0)     // one element, type-checked
```

`elements` is deliberately flat: the dimensions describe how to fold it, which is what the collection converter does for
you. Reach for `PgArray` when you need the dimension metadata or PostgreSQL's non-1 lower bounds, and for nothing else.

Everything in `elements` is what the **codec** for `elementOid` produced, with no converter having run over it: an
`int4[]` holds `Int`s, but an array of a composite holds `PgComposite`s and an array of an enum holds its labels as
`String`s. That is what the rule above rests on — a raw container is the converter layer's input, not its output.
Inside a converter you have a `DeserializationContext` to push each element through the chain; outside one,
`row.get<List<T>>("legions")` has already done exactly that.

### Arrays as a query tool

Two uses of arrays have nothing to do with array *columns*, and are the ones you will reach for most:

```kotlin
// An IN clause, as one parameter instead of N placeholders
session.createNativeQuery("SELECT * FROM senators WHERE province_id = ANY($1)")
    .fetchObjects<Senator>(provinceIds)

// A whole batch of rows in a single statement
session.createNativeQuery("INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)")
    .update(ids, cognomina)
```

Both are covered in [Bulk Writes](bulk-writes.md) — the first under [the `IN` clause](bulk-writes.md#deleting-and-the-in-clause),
the second as the replacement for JDBC batching.

## Ranges and multiranges

PostgreSQL's range types (`int4range`, `numrange`, `tsrange`, `tstzrange`, `daterange`, and any you declare with
`CREATE TYPE ... AS RANGE`) map to the driver's own `Range<T>`, and their multirange counterparts to `MultiRange<T>`.
Both live in `io.github.octaviusframework.driver.type.range`.

### Reading

```kotlin
import io.github.octaviusframework.driver.type.range.Range

val row = session.createNativeQuery("SELECT '[10,20)'::int4range AS term").fetchRowStrict()
val term: Range<Int> = row.get("term")

term.lowerBound         // 10
term.upperBound         // 20
term.isLowerInclusive   // true
term.isUpperInclusive   // false
term.isUpperInfinite    // false
term.isEmpty            // false
```

A range over a date is read exactly the same way — `Range<LocalDate>` from a `daterange`, `Range<Instant>` from a
`tstzrange` — and a range over a registered composite works too, since the bound type goes through the ordinary
converter chain.

### Writing

`rangeOf()` builds one, and the element type is enough for the driver to work out which PostgreSQL range type it is — no
cast required:

```kotlin
import io.github.octaviusframework.driver.type.range.rangeOf

val censusPeriod = rangeOf(lowerBound = 5, upperBound = 15)              // [5,15)
val openEnded = rangeOf(lowerBound = 5)                               // [5,∞)
val closed = rangeOf(lowerBound = 5, upperBound = 15, isUpperInclusive = true)   // [5,15]
val nothing = Range.empty<Int>()                                    // 'empty'

session.createNativeQuery("INSERT INTO censuses (id, period) VALUES ($1, $2)")
    .update(1, censusPeriod)
```

The default bounds are PostgreSQL's own convention — lower inclusive, upper exclusive — and omitting a bound makes that
side infinite. `Range<T>` carries the element class (that is what `rangeOf`'s `reified` parameter captures), and the
driver resolves the range type from it: `Int` → `int4range`.

> [!WARNING]
> **A custom range type over a built-in subtype takes over every unqualified range of that element type.** Resolution
> goes by subtype, and a subtype maps to exactly one range type — so `CREATE TYPE tenure_range AS RANGE (subtype = int4)`
> leaves two candidates for `Int`, and the one that wins is whichever the catalog loaded last, which is the higher OID,
> which is always yours rather than the built-in.
>
> Measured: before creating such a type, `rangeOf(lowerBound = 5, upperBound = 15)` goes out as `int4range`; after, the
> identical call goes out as `tenure_range` — in every session on that database, since the registry is shared. Nothing
> warns you, and it is the *other* ranges that break rather than the new one.
>
> Reading is unaffected — a column's own OID picks its codec, so `int4range` columns still come back as `Range<Int>`.
> Only outbound parameters are ambiguous. Pin them where it matters:
>
> ```kotlin
> session.createNativeQuery("INSERT INTO censuses (period) VALUES ($1)")
>     .update(censusPeriod.withPgType("int4range"))
> ```
>
> The same applies to a second custom range over the same subtype: the highest OID wins, which means the most recently
> created one.

> [!IMPORTANT]
> **PostgreSQL normalizes ranges over discrete types, and what you read back will not always match what you wrote.** For
`int4range`, `int8range` and `daterange` — types with a well-defined "next value" — the server rewrites every range into
> the canonical `[)` form:
>
> | Written        | Stored and read back |
> |:---------------|:---------------------|
> | `[1,5]`        | `[1,6)`              |
> | `(1,5)`        | `[2,5)`              |
> | `(10,∞)`       | `[11,∞)`             |
>
> So a `rangeOf(lowerBound = 5, upperBound = 15, isUpperInclusive = true)` comes back as `[5,16)` — `upperBound == 16`
> and `isUpperInclusive == false`. Nothing was lost and the two describe the same set of integers, but comparing the
> returned object field-by-field against the one you sent will not match. Continuous types (`numrange`, `tsrange`,
`tstzrange`) have no canonical form and are stored as written.

### Multiranges

A multirange is an ordered set of non-overlapping ranges, and PostgreSQL merges and sorts them for you:

```kotlin
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.driver.type.range.MultiRange

val campaigns = multiRangeOf(
    rangeOf(lowerBound = 1, upperBound = 5),
    rangeOf(lowerBound = 10)
)

val back: MultiRange<Int> = session.createNativeQuery("SELECT $1")
    .fetchRowStrict(campaigns).get(0)

back.ranges       // List<Range<Int>>, normalized and ordered by the server
```

`MultiRange.empty<Int>()` builds the empty one. The same normalization caveat applies to every range inside it.

## JSON and JSONB

`json` and `jsonb` columns surface as [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
elements — the library comes with the driver as an `api` dependency, so there is nothing extra to add to your build.

### Reading

```kotlin
import kotlinx.serialization.json.*

val row = session.createNativeQuery("SELECT data FROM dossiers WHERE id = $1").fetchRowStrict(1)

val data: JsonObject = row.get("data")
val status = (data["status"] as JsonPrimitive).content
```

`JsonElement`, `JsonObject`, `JsonArray` and `JsonPrimitive` are all accepted targets; ask for the one the column
actually holds, since a `JsonObject` request against a stored array cannot be satisfied. `row.get<Any>("data")` on a
`json`/`jsonb` column also yields a `JsonElement`, which is what makes a jsonb attribute inside a `Map<String, Any?>`
come out usable.

The raw text is always available too — the codec decodes both types to `String` before any converter runs, so
`row.get<String>("data")` hands you the document verbatim for feeding to a parser of your own.

### Writing

A `JsonElement` parameter needs nothing declared, because the converter states its own target type:

```kotlin
val dossier = buildJsonObject {
    put("status", JsonPrimitive("active"))
    put("province", JsonPrimitive(7))
}

session.createNamedQuery("INSERT INTO dossiers (id, data) VALUES (@id, @data)")
    .update("id" to 1, "data" to dossier)
```

That goes out as **`jsonb`**, which is the right default for a column of either type. When you specifically need
`json` — preserving key order, duplicate keys and whitespace — say so:

```kotlin
data.withPgType(PgStandardType.JSON)
```

A `List<JsonObject>` becomes a `jsonb[]` by the ordinary array inference, and reads back as `List<JsonObject>`.

> [!WARNING]
> **A `String` of JSON is not a JSON parameter.** A bare `String` is declared as `text`, and PostgreSQL will not assign
> text to a `jsonb` column — the statement fails with `StatementException(DATA_TYPE_ERROR)` rather than storing anything:
>
> ```kotlin
> // Fails: the parameter is declared as text
> session.createNativeQuery("INSERT INTO dossiers (id, data) VALUES (1, $1)")
>     .update("""{"status":"active"}""")
>
> // Works: the type is stated
> session.createNativeQuery("INSERT INTO dossiers (id, data) VALUES (1, $1)")
>     .update("""{"status":"active"}""".withPgType("jsonb"))
> ```
>
> This is the same class of problem as `stringtype=unspecified` in pgjdbc, addressed per value rather than per
> connection — see [One default per Kotlin class](type-system.md#one-default-per-kotlin-class).

### Your own DTOs

Two ways to store a `@Serializable` class, and the choice is about how often you do it.

**Once or twice** — serialize at the call site and state the type:

```kotlin
@Serializable
data class Dossier(val status: String, val province: Int)

session.createNativeQuery("INSERT INTO dossiers (id, data) VALUES ($1, $2)")
    .update(1, Json.encodeToString(dossier).withPgType("jsonb"))

val loaded = Json.decodeFromString<Dossier>(row.get<String>("data"))
```

The `Json` is yours either way, and so is one thing it will get wrong unasked: a `BigDecimal` property has no
serializer at all, and a date holding `infinity` is written as the year 999999999, which `::date` refuses.
Mark those `@Contextual` and build the `Json` with `octaviusSerializersModule` from `pg-model` —
[What JSON Does Not Carry](../client/dynamic-dto.md#what-json-does-not-carry) is the same question asked about
`dynamic_dto` payloads, and the answer is the same here.

**Everywhere** — register a converter pair once at startup and pass the DTO directly, with `getDefaultTypeName()`
supplying `jsonb` the same way the built-in one does. [Custom converters](type-system.md#custom-converters) has the full
shape; a JSON-backed DTO is close to the smallest useful example of one.

> [!WARNING]
> **`canConvert` has two axes — the Kotlin type asked for and the PostgreSQL type it came from — and a converter this
specific has to narrow both.** Each half on its own leaks, in opposite directions:
>
> ```kotlin
> // Source only: answers for every json/jsonb value, whatever type was requested
> override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
>     sourceType.name == "json" || sourceType.name == "jsonb"
>
> // Target only: answers for every column that decodes to String - text, xml, inet, enums…
> override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
>     expectedType.classifier == Dossier::class
>
> // Both: what this converter actually handles
> override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
>     expectedType.classifier == Dossier::class &&
>             (sourceType.name == "json" || sourceType.name == "jsonb")
> ```
>
> **Narrow on the source only** and the converter goes *global*. Result converters are indexed by the decoded source
> class, which for both `json` and `jsonb` is `String`, and the newest registration in that bucket wins — so it displaces
> the built-in `JsonElementConverter` for every session against that database, taking with it `row.get<JsonObject>("data")`, 
> the same call on any unrelated JSON column, and `jsonb` attributes nested inside
> composites, since attribute conversion goes through the same registry.
>
> **Narrow on the target only** and it reaches sideways instead. It is registered under `String::class`, so it is
> offered every column that decodes to a `String`: asking for a `Dossier` from a `text`, `xml` or `inet` column produces
`Dossier(raw=just some text)` — no exception, just a value built from the wrong column. A `float8` column is safe only
> by accident, because `Double` is a different bucket and the lookup ends in `NO_CONVERTER_FOUND`.
>
> That second leak is the one to take seriously, because **nothing downstream can catch it**. The mapper does compare
> what a converter produced against the type you asked for — that is what turns a source-only converter's mistake into a 
> `MappingException(CONVERSION_ERROR)` naming it, rather than a bare `ClassCastException` from your own line. But a
> target-only converter returns exactly the class that was requested. It is right about the type and wrong about the data,
> and only its own predicate can know that.

Storing a JSON document inside a composite attribute works in both directions as well, provided the conversion recurses
through the context rather than being done by hand —
`context.convert(source.get("metadata"), typeOf<JsonObject>(), source.getAttributeOid("metadata"))` inside a custom
converter is the shape.

### `json` or `jsonb`

Not a driver question, but the one that follows immediately: `jsonb` is parsed and stored in a binary form — it loses
key order, duplicate keys and whitespace, and gains indexing, containment operators (`@>`) and much faster access.
`json` keeps the document byte-for-byte and reparses it on every access. Default to `jsonb` unless you have a reason to
preserve the exact text; the driver's own default matches that.

## Practical rules and gotchas

* **Nullable elements go in the element type.** `List<Int?>` for an array that may hold `NULL`; `List<Int>` throws
  `REQUIRED_ATTRIBUTE_MISSING` when one turns up.
* **Empty collections need `withPgType`.** And read the `cause` when one slips through — the outer exception is a
  `MappingException`, the useful one under it is a `TypeException`.
* **Discrete ranges come back normalized.** `[1,5]` is stored as `[1,6)`. Compare ranges by what they contain, not field
  by field.
* **A custom range type over a built-in subtype hijacks unqualified parameters** of that element type, database-wide.
  Pin those with `withPgType`.
* **Narrow a custom converter's `canConvert` on both axes** — the requested Kotlin type *and* the source PostgreSQL
  type. Either half alone leaks: one claims every JSON value in the database, the other every column that decodes to the
  same class.
* **A JSON `String` needs its type stated; a `JsonElement` does not.** The converter declares `jsonb` on its own.
* **`= ANY($1)` is how a list reaches an `IN` clause** — one parameter, one plan, any length.
  See [Bulk Writes](bulk-writes.md#deleting-and-the-in-clause).
* **Reach for `PgArray` only for dimension metadata.** Everything else is easier as a `List`.
