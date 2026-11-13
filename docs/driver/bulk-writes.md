# Bulk Writes

*Rome did not feed a million people by sending a cart to each household. The grain fleet sailed from Alexandria,
unloaded at Portus, and the whole harvest of a province crossed the sea in one passage. The arithmetic on this page is
the same one: ten thousand rows sent a statement at a time pay for ten thousand crossings, and the same rows sent as
arrays in a single statement pay for one.*

Many rows go in as one statement with one array parameter per column, which `UNNEST` turns back into rows. That is one
round trip and one plan whatever the row count; [Performance](performance.md#writing) has what it saves against one
`INSERT` at a time. There is no `addBatch()` / `executeBatch()` ([why](octavius-vs-jdbc.md#3-jdbc-batching)), and the
array form covers `UPDATE` and `DELETE` as well as `INSERT`.

Contents:

* [Inserting](#inserting)
* [Updating](#updating)
* [Deleting, and the `IN` clause](#deleting-and-the-in-clause)
* [Upserts](#upserts)
* [Reading values back with `RETURNING`](#reading-values-back-with-returning)
* [Composites, enums and your own classes](#composites-enums-and-your-own-classes)
* [Choosing a batch size](#choosing-a-batch-size)
* [When to reach for COPY instead](#when-to-reach-for-copy-instead)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## Inserting

Turn the rows sideways, one list per column, and let `UNNEST` zip them back together:

```kotlin
data class Senator(val id: Int, val cognomen: String)

val senators: List<Senator> = loadNewSenators()
val ids = senators.map { it.id }
val cognomina = senators.map { it.cognomen }

val inserted: Long = session.createNativeQuery(
    "INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)"
).executeUpdate(ids, cognomina)
```

`executeUpdate()` returns the number of rows inserted. A `List<Int>` goes out as `int4[]` and a `List<String>` as
`text[]`, the element type taken from the first non-null element; [Arrays](arrays-ranges-json.md#writing) has the rest.

> [!WARNING]
> **Arrays of unequal length do not fail; they pad with `NULL`.** `UNNEST` returns as many rows as the longest array,
> so a list that lost an element somewhere upstream inserts rows with nulls, and nothing complains unless a column is
> `NOT NULL`. Build the lists in one pass over one collection, as above.

### When a cast earns its place

None of the statements on this page needs a cast. The driver declares the type of every array it sends, and PostgreSQL
converts what it can by itself: an `int4[]` fills a `bigint[]` column, its unnested rows join a `bigint` key, and a
`List<String>` under `= ANY($1)` compares with a `varchar` column. Two cases need one:

* **An array compared whole.** `=`, `@>`, `&&` and `<@` between two arrays want the same array type on both sides, so
  `varchar[] = text[]` and `bigint[] @> int4[]` fail with `StatementException(UNDEFINED_OBJECT)` (`42883`, "operator
  does not exist"). Cast the parameter to the column's type: `WHERE tags @> $1::varchar[]`.
* **A conversion PostgreSQL does not make unasked**, most often `text` to `jsonb`: a `List<String>` goes out as
  `text[]`, and `UNNEST($1)` into a `jsonb` column fails with `StatementException(DATA_TYPE_ERROR)` (`42804`). Cast it
  in SQL: `UNNEST($1::jsonb[])`.

### Empty batches need a type

An `emptyList()` has no element to take a type from, so it fails with `MappingException(CONVERSION_ERROR)` caused by
`TypeException(TYPE_NOT_FOUND)`. Return early where an empty batch means no work, or state its type as
[Empty collections need their type stated](arrays-ranges-json.md#empty-collections-need-their-type-stated) shows.

## Updating

Join the table against the unnested arrays:

```kotlin
session.createNativeQuery(
    """
    UPDATE senators AS s
    SET rank = u.rank,
        province_id = u.province
    FROM UNNEST($1, $2, $3) AS u(id, rank, province)
    WHERE s.id = u.id
"""
).executeUpdate(ids, ranks, provinces)
```

The alias `u(id, rank, province)` names the unnested columns.

## Deleting, and the `IN` clause

`= ANY` takes the array as it is:

```kotlin
session.createNativeQuery("DELETE FROM senators WHERE id = ANY($1)")
    .executeUpdate(ids)
```

That is also how a list reaches an `IN` clause. The driver does not expand a list into a placeholder per element; a
list is one array parameter, so the clause is written `= ANY($1)`:

```kotlin
val senators = session.createNativeQuery("SELECT * FROM senators WHERE province_id = ANY($1)")
    .fetchObjects<Senator>(provinceIds)
```

## Upserts

`ON CONFLICT` works on the bulk insert as on any other:

```kotlin
session.createNativeQuery(
    """
    INSERT INTO senators (id, cognomen)
    SELECT * FROM UNNEST($1, $2)
    ON CONFLICT (id) DO UPDATE SET cognomen = EXCLUDED.cognomen
"""
).executeUpdate(ids, cognomina)
```

## Reading values back with `RETURNING`

```kotlin
val newIds: List<Long> = session.createNativeQuery(
    """
    INSERT INTO senators (cognomen) SELECT * FROM UNNEST($1)
    RETURNING id
"""
).fetchFields(cognomina)
```

> [!IMPORTANT]
> **`RETURNING` does not promise the order you sent.** Nothing orders it, and a row skipped by `ON CONFLICT DO NOTHING`
> or by a `BEFORE` trigger returning `NULL` is missing from it, so pairing `newIds` with `cognomina` by position can
> give an id the wrong name. Carry the correlation in the data: number the rows `WITH ORDINALITY`, or return a key you
> sent.
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

## Composites, enums and your own classes

A data class registered with `registerAutoComposite` is a PostgreSQL type like any other, so a `List<Senator>` goes out
as `senator[]` and the whole batch is one parameter:

```kotlin
session.typeManager.registerAutoComposite<Senator>()   // once, at startup

session.createNativeQuery(
    """
    INSERT INTO senators (id, cognomen)
    SELECT s.id, s.cognomen FROM UNNEST($1) AS s
"""
).executeUpdate(senators)
```

A registered enum goes out the same way, a `List<LegioStatus>` as `legio_status[]` with no cast. The composite type has
to exist in the database; it is often the table's own row type. See
[Registering your own mappings](type-system.md#registering-your-own-mappings).

A `composite[]` allocates more than the same rows as scalar arrays, and `registerAutoComposite` falls behind a
hand-written `ParameterConverter` as the class widens;
[Performance](performance.md#reflection-or-a-hand-written-converter) measures both.
Use scalar arrays on the path that moves the most rows, and the composite form where the call would otherwise take
eight arrays. For a wide class on a hot path, [a hand-written `ParameterConverter`](type-system.md#custom-converters)
replaces the reflection.

## Choosing a batch size

The whole batch is encoded into the connection's parameter buffer before anything is sent, so while the statement runs
it is in memory twice: as your objects and in binary form. The buffer shrinks back to `initialParameterWriterCapacity`
after a statement that pushed it past `maxParameterWriterCapacity`
([both configurable](initialization.md#network-and-limits)).

Chunk at 10 000–50 000 rows. The cost per statement is spread thin long before that, and smaller chunks keep memory
and lock time predictable:

```kotlin
session.transaction.required {
    senators.chunked(10_000).forEach { chunk ->
        createNativeQuery("INSERT INTO senators (id, cognomen) SELECT * FROM UNNEST($1, $2)")
            .executeUpdate(chunk.map { it.id }, chunk.map { it.cognomen })
    }
}
```

## When to reach for COPY instead

| Situation                                              | Reach for                                                            |
|:-------------------------------------------------------|:---------------------------------------------------------------------|
| Rows are objects in memory, tens to tens of thousands  | `UNNEST`                                                             |
| You need `RETURNING`, `ON CONFLICT`, `UPDATE`/`DELETE` | `UNNEST` — `COPY` offers none of them                                |
| Data is already a file or a stream                     | `COPY` — no reason to parse it into objects first                    |
| Hundreds of thousands of rows and up, plain insert     | `COPY` — constant memory, and no array to build                      |
| A full table reload                                    | `COPY` inside a transaction with the `TRUNCATE`, for `WITH (FREEZE)` |

`UNNEST` builds the whole batch in memory; [`COPY`](copy.md) streams it.

## Practical rules and gotchas

* **Build the parallel lists in one pass.** Arrays of unequal length pad with `NULL` instead of failing.
* **Guard or type empty batches.** An `emptyList()` has no element type to send.
* **Do not pair `RETURNING` rows with your input by position.** Use `WITH ORDINALITY` or return a key you sent.
* **`= ANY($1)` is the `IN` clause.** A list is one parameter, never a placeholder per element.
* **Scalar arrays are the cheapest form.** A `composite[]` allocates more for the same rows.
* **Chunk.** 10 000–50 000 rows per statement.
