# Bulk Writes

*Rome did not feed a million people by sending a cart to each household. The grain fleet sailed from Alexandria,
unloaded at Portus, and the whole harvest of a province crossed the sea in one passage. The arithmetic on this page is
the same one: ten thousand rows sent a statement at a time pay for ten thousand crossings, and the same rows sent as
arrays in a single statement pay for one.*

Past a handful of rows, the shape of the statement matters far more than the driver underneath it. Inserting 10 000 senators one `INSERT` at a time costs ~282 ms; the same rows go in through a single statement carrying arrays in **~7.6 ms** — [37× faster](performance.md#writing), and the same ratio holds for `pgjdbc`.

Octavius has no `addBatch()` / `executeBatch()`: JDBC batching is [one of the things it dropped](octavius-vs-jdbc.md#3-jdbc-batching). What replaces it is PostgreSQL's own answer — pass each column as an array, let the server zip them back into rows — which is not a workaround for a missing feature but the faster path in both drivers, and the only one of the two that works for `UPDATE` and `DELETE` as well.

Contents:
* [Why one statement beats a batch](#why-one-statement-beats-a-batch)
* [Inserting](#inserting)
* [Updating](#updating)
* [Deleting, and the `IN` clause](#deleting-and-the-in-clause)
* [Upserts](#upserts)
* [Reading values back with `RETURNING`](#reading-values-back-with-returning)
* [Composites, enums and your own classes](#composites-enums-and-your-own-classes)
* [Choosing a batch size](#choosing-a-batch-size)
* [When to reach for COPY instead](#when-to-reach-for-copy-instead)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## Why one statement beats a batch

Row-at-a-time insertion pays for a full Parse/Bind/Execute cycle and a round trip per row. JDBC batching pipelines those messages so they travel together, but the server still binds and executes each row separately — which is why it lands where it does in [the benchmarks](performance.md#writing):

| Strategy for 10 000 rows                | Octavius     | pgjdbc       |
|:----------------------------------------|:-------------|:-------------|
| Single inserts                          | 281.6 ± 4.1  | 221.2 ± 2.6  |
| JDBC batching                           | n/a          | 25.55 ± 0.32 |
| JDBC batching + `reWriteBatchedInserts` | n/a          | 7.35 ± 0.48  |
| **`UNNEST`**                            | 7.56 ± 0.41  | 5.96 ± 0.50  |

Milliseconds per operation, lower is better. `UNNEST` in Octavius matches pgjdbc's fastest batching mode and beats plain batching by 3.4×.

The reason is that `UNNEST` collapses the whole load into **one** statement: one Parse, one Bind, one Execute, one round trip, one plan. Ten thousand rows leave as *two* parameters — an `int[]` and a `text[]` — rather than twenty thousand.

That last detail has a hard consequence beyond speed. The Bind message counts its parameters in a 16-bit field, so **no statement can carry more than 65 535 parameters**: a multi-row `VALUES` list with two columns hits that ceiling at ~32 000 rows and cannot go further. An `UNNEST` statement sends one parameter per *column* no matter how many rows ride inside it, so the ceiling never enters the picture.

And unlike `reWriteBatchedInserts`, which rewrites `INSERT` and nothing else, the array form applies unchanged to every kind of DML.

## Inserting

Turn your rows sideways — one list per column — and let `UNNEST` zip them back together:

```kotlin
data class Senator(val id: Int, val cognomen: String)

val senators: List<Senator> = loadNewSenators()
val ids = senators.map { it.id }
val cognomina = senators.map { it.cognomen }

val inserted: Long = session.createNativeQuery(
    "INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)"
).update(ids, cognomina)
```

`update()` returns the row count from the server's own `INSERT n` tag, exactly as it would for a single-row insert.

A Kotlin `List` becomes a PostgreSQL array with no ceremony: the parameter converter walks the collection, infers the element type from the first non-null element, and sends a binary array under the matching OID — `List<Int>` as `int4[]`, `List<String>` as `text[]`. Named parameters work the same way:

```kotlin
session.createNamedQuery(
    "INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST(@ids, @cognomina)"
).update("ids" to ids, "cognomina" to cognomina)
```

### When a cast earns its place

The statements above carry none and need none: the driver declares an OID for every array it sends, so `UNNEST($1)` is
planned knowing it was handed an `int4[]`. Wherever the array is taken apart before anything is compared — unnested into
rows, or matched element-wise by `= ANY($1)` — only the element types have to meet, and PostgreSQL brings those together
itself: an `int4[]` fills a `bigint[]` column, its unnested rows join a `bigint` key, and a `List<String>` matches a
`varchar` one.

**An array compared whole is the exception.** The `anyarray` operators resolve both sides to one type and coerce
neither, so an element type that merely converts is not close enough: `varchar[] = text[]` stops at *"operator does not
exist: character varying[] = text[]"* (`42883`, arriving as `StatementException(UNDEFINED_OBJECT)`), and `@>`, `&&` and
`<@` stop the same way. Nor is it a `varchar` quirk — `bigint[] @> int4[]` fails identically. A `List<String>` goes out
as `text[]` and a `List<Int>` as `int4[]`, so a `varchar[]` or `bigint[]` column has to be told:
`WHERE tags @> $1::varchar[]`.

The other case is the conversion PostgreSQL will not make unasked, and `text` to `jsonb` is the one to watch:
`INSERT INTO dossiers (data) SELECT * FROM UNNEST($1)` stops at *"column `data` is of type jsonb but expression is of
type text"* (`42804`, arriving as `StatementException(DATA_TYPE_ERROR)`). Either say it in the SQL —
`UNNEST($1::jsonb[])` — or say it on the Kotlin side with [`withPgType`](type-system.md#pgtyped).

What `withPgType` will *not* do is widen. It declares what you are sending, and the OID it names picks the codec that
encodes your values, so `ids.withPgType(PgStandardType.INT8_ARRAY)` on a `List<Int>` asks the `int8` codec for an `Int`
and fails with `MappingException(NO_CONVERTER_FOUND)` — `path` naming the element — before anything is sent. Build a
`List<Long>` if you want `int8` on the wire; cast in SQL if you want the server to convert.
See [Arrays](arrays-ranges-json.md#writing) for the two layers side by side.

### Empty batches need a type

Erasure leaves nothing in an `emptyList()` for the driver to infer an element type from, so an empty batch fails rather than guessing: `TypeException(TYPE_NOT_FOUND)`, arriving as the `cause` of the `MappingException(CONVERSION_ERROR)` that the converter layer wraps everything in. Two ways out, and both are fine:

```kotlin
// Guard, when an empty batch means there is no work to do
if (ids.isEmpty()) return 0

// Or state the type, when an empty batch is legitimate
session.createNativeQuery("INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)")
    .update(ids.withPgType(PgStandardType.INT4_ARRAY), cognomina.withPgType(PgStandardType.TEXT_ARRAY))
```

`PgStandardType` covers the built-in array types; `withPgType("legio_status", isArray = true)` names one of your own. See [PgTyped](type-system.md#pgtyped) for the whole mechanism.

> [!WARNING]
> **Arrays of unequal length do not fail — they pad with `NULL`.** `UNNEST` produces as many rows as the *longest* array it was given, filling the shorter ones with nulls, so a list that lost an element to a filter somewhere upstream inserts null rows instead of raising anything. PostgreSQL only complains if a null then hits a `NOT NULL` column. Build the parallel lists in one pass over one collection, as above, rather than assembling them separately.

## Updating

This is where the array form earns its keep, because `reWriteBatchedInserts` — the one JDBC batching mode that is genuinely fast — does nothing for `UPDATE`. Join the table against the unnested arrays:

```kotlin
session.createNativeQuery("""
    UPDATE senators AS s
    SET rank = u.rank,
        province_id = u.province
    FROM UNNEST($1, $2, $3) AS u(id, rank, province)
    WHERE s.id = u.id
""").update(ids, ranks, provinces)
```

The `AS u(id, rank, province)` alias is what makes the unnested columns addressable; without it they arrive as `unnest`, `unnest1`, and so on. The returned count is the number of rows actually updated, which is **not** necessarily the length of your arrays — an id that matches nothing simply updates nothing, silently. If that matters, compare the count against the batch size and act on the difference.

## Deleting, and the `IN` clause

A delete needs no zipping, only membership, so `= ANY` takes the array directly:

```kotlin
session.createNativeQuery("DELETE FROM senators WHERE id = ANY($1)")
    .update(ids)
```

The same construction answers a question that has nothing to do with bulk writing: **how to pass a list to an `IN` clause.** In JDBC that means generating `?, ?, ?` to match the size of the list and binding each element — a different statement string for every distinct list length, and a new plan for each. Here the list is one parameter and the statement is a constant:

```kotlin
val senators = session.createNativeQuery("SELECT * FROM senators WHERE province_id = ANY($1)")
    .fetchObjects<Senator>(provinceIds)
```

`x = ANY(array)` is exactly equivalent to `x IN (...)`, and `x <> ALL(array)` covers `NOT IN`. Note that neither matches `NULL`, the same way `IN` does not.

## Upserts

`ON CONFLICT` attaches to the `INSERT` and behaves normally, which makes a bulk upsert a one-liner and is a capability [`COPY`](copy.md) does not have at all:

```kotlin
session.createNativeQuery("""
    INSERT INTO senators (id, cognomen)
    SELECT * FROM UNNEST($1, $2)
    ON CONFLICT (id) DO UPDATE SET cognomen = EXCLUDED.cognomen
""").update(ids, cognomina)
```

One caveat is PostgreSQL's, not the driver's: a single statement cannot update the same row twice, so a batch containing the same conflict key more than once fails with *"ON CONFLICT DO UPDATE command cannot affect row a second time"* (`21000`, arriving as `StatementException(INVALID_DEFINITION)`). Deduplicate the batch by key before sending it.

## Reading values back with `RETURNING`

`RETURNING` works as it always does, so generated ids come back as a list:

```kotlin
val newIds: List<Long> = session.createNativeQuery("""
    INSERT INTO senators (cognomen) SELECT * FROM UNNEST($1)
    RETURNING id
""").fetchFields(cognomina)
```

> [!IMPORTANT]
> **`RETURNING` does not promise the rows come back in the order you sent them.** SQL guarantees no ordering without an `ORDER BY`, and there is nothing here to order by that you did not supply yourself. Zipping `newIds` against `cognomina` by position is the kind of assumption that holds in testing and breaks under a parallel plan.
>
> If you need to correlate results with inputs, carry the correlation in the data. `WITH ORDINALITY` numbers the unnested rows for you:
>
> ```kotlin
> data class Assigned(val ordinality: Long, val id: Long)
>
> val assigned: List<Assigned> = session.createNativeQuery("""
>     INSERT INTO senators (cognomen, batch_position)
>     SELECT c.cognomen, c.ordinality FROM UNNEST($1) WITH ORDINALITY AS c(cognomen, ordinality)
>     RETURNING batch_position AS ordinality, id
> """).fetchObjects(cognomina)
> ```
>
> Returning a natural key you already sent works just as well and needs no extra column.

## Composites, enums and your own classes

Turning rows sideways is tedious past three columns, and it does not have to be the only option. A data class registered with `registerAutoComposite` is a PostgreSQL type like any other, so a `List<Senator>` becomes a `senator[]` and the whole batch travels as **one** parameter:

```kotlin
session.typeManager.registerAutoComposite<Senator>()   // once, at startup

session.createNativeQuery("""
    INSERT INTO senators (id, cognomen)
    SELECT s.id, s.cognomen FROM UNNEST($1) AS s
""").update(senators)
```

The element OID is inferred from the first element the same way a scalar array's is — the composite registration supplies it, and a registered enum supplies its own type name in the same position, so a `List<LegioStatus>` goes out as `legio_status[]` without a cast. See [Registering your own mappings](type-system.md#registering-your-own-mappings).

The composite type has to exist in the database either way — it is often the table's own row type, which PostgreSQL creates for you.

### What the composite form costs

[Measured](performance.md#reflection-or-a-hand-written-converter) on the same 10 000 rows, against the parallel-scalar-arrays form above. The row here is two columns, an `int` and a short `text`, which is worth keeping in mind before reading the multipliers as constants:

| Form                                             | Time        | Allocated |
|:-------------------------------------------------|:------------|:----------|
| Parallel scalar arrays                           | 7.10 ± 0.44 | 0.93 MB   |
| `composite[]`, hand-written `ParameterConverter` | 7.01 ± 0.39 | 2.05 MB   |
| `composite[]`, `registerAutoComposite`           | 8.65 ± 0.33 | 4.93 MB   |

**On time, the composite form is free** — 7.01 against 7.10 with the intervals overlapping — as long as the converter is one you wrote. Going through `registerAutoComposite` instead costs about a quarter, and that difference is outside the noise.

**On memory it is never free.** For these rows, composites allocate 2.2× the scalar form even at their cheapest, and the reflective path 5.3×. Those multipliers are not constants — a wider class or heavier field types shift them in both columns — but the ordering holds, and it lands on top of the arrays you already built. See [Choosing a batch size](#choosing-a-batch-size).

So: reach for parallel scalar arrays on the path that moves the most rows, and for the composite form when the call site would otherwise take eight arrays and a good memory. Reflection is the convenience on top of that, and the one part of it with a price tag worth checking — the example above uses it; [a hand-written `ParameterConverter`](type-system.md#custom-converters) is what buys the middle row of that table back.

## Choosing a batch size

Everything in the batch is serialized into the connection's parameter buffer **before the first byte goes out**, so the whole load exists twice in memory for the duration: once as your Kotlin objects, once in binary form. The buffer grows to fit and then shrinks back to `initialParameterWriterCapacity` after any statement that pushed it past `maxParameterWriterCapacity` ([both configurable](initialization.md#network-and-limits)), so a large batch does not permanently inflate the connection — it does allocate at the time.

Two hard limits sit further out: a single PostgreSQL array value cannot exceed 1 GB, and `UNNEST` materializes its rows server-side.

How much that is depends on what a row holds, not only on how many there are. For the [benchmark's](performance.md#reflection-or-a-hand-written-converter) two-column rows — an `int` and a short `text` — 10 000 of them come to ~0.93 MB per statement as parallel scalar arrays and ~4.93 MB as a reflectively built `composite[]`. A wider class, longer strings or nested structures move both figures, so measure your own shape rather than scaling these.

In practice the gain is nearly all captured well before any of that. **Chunk at 10 000–50 000 rows** and loop; the per-statement overhead you are trying to amortize disappears within the first thousand, and smaller chunks keep both memory and lock duration predictable:

```kotlin
session.transaction.required {
    senators.chunked(10_000).forEach { chunk ->
        createNativeQuery("INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)")
            .update(chunk.map { it.id }, chunk.map { it.cognomen })
    }
}
```

Wrapping the loop in a transaction is what makes the chunks all-or-nothing; without it each statement commits on its own and a failure halfway leaves the earlier chunks applied.

## When to reach for COPY instead

[`COPY`](copy.md) is the next step up, and the boundary is less about row count than about what you need from the statement:

| Situation                                              | Reach for                                                            |
|:-------------------------------------------------------|:---------------------------------------------------------------------|
| Rows are objects in memory, tens to tens of thousands  | `UNNEST`                                                             |
| You need `RETURNING`, `ON CONFLICT`, `UPDATE`/`DELETE` | `UNNEST` — `COPY` offers none of them                                |
| Data is already a file or a stream                     | `COPY` — no reason to parse it into objects first                    |
| Hundreds of thousands of rows and up, plain insert     | `COPY` — constant memory, and no array to build                      |
| A full table reload                                    | `COPY` inside a transaction with the `TRUNCATE`, for `WITH (FREEZE)` |

`UNNEST` builds the entire batch in memory; `COPY` streams it. That is the practical difference at scale, and the reason the chunking loop above exists.

## Practical rules and gotchas

* **Build the parallel lists in one pass.** Arrays of different lengths pad with `NULL` instead of failing — the single easiest way to write garbage rows at speed.
* **`update()` counts what the server did, not what you sent.** For a bulk `UPDATE` the difference is exactly the ids that matched nothing; check it if that is not supposed to happen.
* **Guard or type your empty batches.** `emptyList()` carries no element type, so it fails with `TYPE_NOT_FOUND` unless wrapped in `withPgType`.
* **Scalar arrays are the cheapest form.** On two-column rows a `composite[]` allocated 2.2× as much, and 5.3× when `registerAutoComposite` built it; time was a tie except for the reflective path. Your own shape decides the size of that, not the ratio quoted here.
* **Do not zip `RETURNING` results against your input by position.** Use `WITH ORDINALITY` or return a key you supplied.
* **Deduplicate before an `ON CONFLICT DO UPDATE`.** The same key twice in one statement is an error, not a last-write-wins.
* **`= ANY($1)` is the `IN` clause.** One parameter, one plan, any list length — and `<> ALL($1)` for `NOT IN`.
* **Chunk, and wrap the loop in a transaction.** 10 000–50 000 rows per statement captures the win without holding an unbounded buffer.
* **Reuse the query object across chunks** if you are looping: `createNativeQuery` snapshots the codec dictionary at construction, and building it once per loop rather than once per chunk saves that work.
