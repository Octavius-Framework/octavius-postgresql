# Queries

*The praetor's edict published the formulae in advance: a fixed core naming the claim, and beside it the
clauses a case might or might not need — an exceptio if the defendant had a defence, a replicatio if the
claimant had an answer to it. The parties did not compose the words. They settled which clauses applied, and
the formula was assembled from that.*

The builders here do the assembling and nothing else. What goes in each clause is SQL, and it is passed through
unread.

> The terminal methods — `fetchRows`, `fetchObjects<T>`, `fetchField<T>`, `forEach*`, `update` — are the
> driver's own, under the driver's names, with the driver's meanings. This page does not describe them; see
> [Executing Queries](../driver/queries.md) for the whole family, nullability, the strict variants and
> streaming.

## Every Clause Is SQL

```kotlin
val senators = db.select("id", "cognomen", "count(*) OVER () AS total")
    .from("senate s JOIN provinces p ON p.id = s.province_id")
    .where("p.name = @province AND s.rank <> 'RETIRED'")
    .groupBy("s.id, s.cognomen")
    .having("count(*) > 1")
    .orderBy("s.cognomen DESC NULLS LAST")
    .fetchObjects<Senator>("province" to "Gallia")
```

Nothing there is parsed. `from` takes the join because that *is* the join; `select` takes a window function
because a column list is a list of expressions. There is no `.join()`, no `.eq()`, and no expression tree,
because a second dialect of SQL to learn buys nothing when the first one is already exact.

What the builder contributes is the mechanical part:

- the keywords and the order they have to go in,
- the column list paired with its own `@name` placeholders, so the two cannot drift apart,
- the clauses that disappear when they have nothing to say.

Every builder carries `with(name, query)` and `recursive()` for a `WITH` clause, so a CTE — recursive or not —
needs no raw SQL either.

### A name that comes from outside

Passed through unread cuts both ways. A value belongs in an `@name` placeholder and is never part of the
statement — that much the builders enforce, because `value(column)` and `setValue(column)` write the
placeholder themselves. A **name** has nowhere else to go: `orderBy`, `from`, `insertInto(table)`, the columns
handed to `select`, and the keys of `values(map)` and `setValues(map)` are all SQL text, and text that arrived
in a request is SQL text like any other.

A sort column taken from a query parameter is how this goes wrong most often:

```kotlin
// The sort key is a name, so it is SQL. Map the ones you allow onto the ones you wrote.
val ordering = when (sortParam) {
    "name" -> "s.cognomen"
    "rank" -> "s.rank_order"
    else   -> "s.id"
}

db.select("*").from("senate").orderBy("$ordering ${if (descending) "DESC" else "ASC"}")
```

A fixed `when` is the answer whenever the set of names is known, which it nearly always is — it also settles
what happens to a name that is not in it. Where the name genuinely cannot be mapped onto one you wrote — a
table per tenant, a column discovered from the catalog — `quoteAsPgIdentifier()` is the escape hatch, and it
is the driver's own: see
[Quoting a name that comes from outside](../driver/queries.md#quoting-a-name-that-comes-from-outside) for what
it does and for the case-sensitivity it brings with it.

```kotlin
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier

db.select("*").from(tenantTable.quoteAsPgIdentifier()).fetchRows()
```

### The four builders

| Builder                | Required before it renders                                     |
|------------------------|----------------------------------------------------------------|
| `db.select(…)`         | `from`, unless the projection needs no table                   |
| `db.insertInto(table)` | Something to insert: `value`, `values`, or `fromSelect`        |
| `db.update(table)`     | Something to set, **and a `WHERE`**                            |
| `db.deleteFrom(table)` | **A `WHERE`**                                                  |

**An `UPDATE` or `DELETE` built here requires a `WHERE`.** Emptying a table is a statement worth having to
mean, and a builder that lets it fall out of a `null` filter is how it happens by accident. Where it is meant,
`rawQuery` says so in the diff and in code review:

```kotlin
db.rawQuery("DELETE FROM staging_census").update()
```

The failure is an `InvalidOperationException` naming the table and pointing at `rawQuery`, and it is raised
when the SQL is rendered — which is when a terminal runs, or when
[a plan is validated](plans.md#checked-before-it-runs).

Beyond `with` / `recursive`, which all four carry, each has the clauses its statement has:

| Builder                | Clauses                                                                                                        |
|------------------------|----------------------------------------------------------------------------------------------------------------|
| `db.select(…)`         | `from`, `fromSubquery`, `where`, `groupBy`, `having`, `orderBy`, `limit`, `offset`, `page`, `forUpdate`        |
| `db.insertInto(table)` | `value`, `values`, `valueExpression`, `valuesExpressions`, `columns` + `fromSelect`, `onConflict`, `returning` |
| `db.update(table)`     | `setValue`, `setValues`, `setExpression`, `setExpressions`, `from`, `where`, `returning`                       |
| `db.deleteFrom(table)` | `using`, `where`, `returning`                                                                                  |

`fromSubquery(sql, alias)` parenthesises and aliases for you, which is all it does — `from("(…) AS t")` is the
same statement. `using` is `DELETE`'s join clause, as `from` is `UPDATE`'s. `columns(…)` names the target
columns of an `INSERT … SELECT` and goes with `fromSelect`, never with `values`: the `VALUES` forms declare
their columns themselves, and an `INSERT` taking its rows from both is refused when it renders. `fromSelect`
takes SQL, so [another builder's `toSql()`](#a-query-is-a-value) fits there.

## Clauses That Disappear

This is the reason to reach for a builder at all:

```kotlin
db.select("id", "cognomen")
    .from("senate")
    .where(filter)          // null or blank: no WHERE at all
    .orderBy(sortColumn)    // null: no ORDER BY
    .limit(pageSize)        // null: no LIMIT
    .fetchObjects<Senator>(params)
```

A filter assembled at runtime is otherwise a string-concatenation problem with a dangling `AND` in it. Here the
clause either has something to say or is not rendered.

`offset` and `page` are the exception and take non-null values, an offset without a limit being a question
rather than a filter. `page(page, size)` is `limit(size).offset(page * size)`, counted from zero.

`forUpdate(of, mode)` adds row locking. `of` names which of the query's tables to lock, or `null` for all of
them; `mode` is `LockWaitMode.NOWAIT` (fail rather than wait) or `LockWaitMode.SKIP_LOCKED` (leave the locked
rows out and carry on), `null` waiting for them. Worth calling only inside
[`db.transaction { }`](transactions-failures.md#propagation): the lock is held until the transaction ends, and
outside one that is until the statement finishes, which is no lock at all.

## `QueryFragment`

A condition and the parameters it names belong together; kept apart, one of them eventually goes missing.

```kotlin
val filters = listOfNotNull(
    request.province?.let { "p.name = @province" withParam ("province" to it) },
    request.minRank?.let { "s.rank >= @rank" withParam ("rank" to it) },
    request.cognomen?.let { "s.cognomen ILIKE @cognomen" withParam ("cognomen" to "%$it%") }
)

val where = filters.join(" AND ")

val senators = db.select("id", "cognomen").from("senate s JOIN provinces p ON p.id = s.province_id")
    .where(where.sql)
    .fetchObjects<Senator>(where.params)
```

`withParam` takes one parameter and `withParams` a map. `join` merges what survives:

- **Empty fragments are dropped**, so a `listOfNotNull` with three `null`s in it joins to an empty fragment,
  and `where("")` renders no `WHERE`. `prefix` and `postfix` go with it, so nothing can produce a bare
  `WHERE ()`.
- **Each fragment is parenthesised.** Not cosmetic: `"a = 1 OR b = 2"` joined to `"c = 3"` with `" AND "` is
  `(a = 1 OR b = 2) AND (c = 3)`, and without the parentheses `AND` binds tighter and quietly returns different
  rows. Turn it off with `addParenthesis = false` only where every fragment is a single term.
- **Two fragments naming one parameter with different values is refused.** One would replace the other, and
  which one would depend on the order the filters happened to be listed in.

`prefix` is for a hand-written query, where nothing supplies the keyword:

```kotlin
val where = filters.join(" AND ", prefix = "WHERE ")
db.rawQuery("SELECT id, cognomen FROM senate ${where.sql}").fetchObjects<Senator>(where.params)
```

The builders supply their own keyword, so leave it empty there.

## A Value, or an Expression

`value("tribe")` and `setValue("tribe")` write the column on one side and the `@tribe` placeholder on the
other, which is the pairing the builder exists to keep. Some assignments have no value to send, though, because
what goes on the right is computed where the row is:

```kotlin
db.insertInto("edicts")
    .values(listOf("province_id", "text"))
    .valueExpression("issued_at", "now()")
    .valueExpression("seq", "nextval('edict_seq')")
    .returning("id")
    .fetchFieldStrict<Int>("province_id" to 3, "text" to body)
```

`valueExpression(column, sql)` puts SQL on the right instead of a placeholder, and `valuesExpressions(map)`
does several at once. `UPDATE` has the same pair under `setExpression` / `setExpressions`, and there it reaches
the case a parameter cannot express at all — an expression may read the column it is assigning:

```kotlin
db.update("legion_supplies")
    .setExpression("quantity", "quantity - @taken")
    .setValue("last_drawn_at")
    .where("id = @id")
    .update("taken" to 1, "last_drawn_at" to now, "id" to supplyId)
```

`quantity - @taken` is the decrement done in the database. Reading the row, subtracting in Kotlin and sending
the result back is two round trips and a lost update between them; there is no parameter that can stand for the
old value, because the old value never left the server.

Note what that example is not. The expression still carries `@taken`, so this is not "an expression *instead
of* parameters" but an expression *around* them — the placeholders work inside it exactly as everywhere else,
and every value in the statement is still bound. The rule that follows is
[the one above](#a-name-that-comes-from-outside) and not a new one: an expression is SQL text, passed through
unread, so nothing that arrived in a request belongs in the string. It belongs in an `@name` inside it.

## Upserts

`onConflict { }` configures the `ON CONFLICT` clause:

```kotlin
db.insertInto("census")
    .values(listOf("citizen_id", "tribe", "assessed_at"))
    .onConflict {
        onColumns("citizen_id")
        doUpdate("tribe = excluded.tribe, assessed_at = excluded.assessed_at")
    }
    .update("citizen_id" to id, "tribe" to tribe, "assessed_at" to assessedAt)
```

Two decisions go in it. **What to conflict on** — `onColumns("citizen_id", …)` for a unique index over those
columns, or `onConstraint("census_citizen_uq")` for a named one. **What to do about it** — `doNothing()`, or
`doUpdate(…)`.

The target is optional and the action is not: a clause saying what to conflict on and nothing about what to do
is not a clause, and it is refused when the query renders. Whether you may leave the target out is
PostgreSQL's rule rather than this builder's — it infers one for `DO NOTHING`, and requires one for
`DO UPDATE`.

`doUpdate` takes the `SET` body as it stands, as column-to-expression pairs, or as a map — the same assignment
three ways:

```kotlin
doUpdate("tribe = excluded.tribe, assessed_at = now()")
doUpdate("tribe" to "excluded.tribe", "assessed_at" to "now()")
doUpdate(mapOf("tribe" to "excluded.tribe", "assessed_at" to "now()"))
```

`excluded` is PostgreSQL's name for the row that could not be inserted, so `excluded.tribe` is the value this
statement was carrying and a bare `tribe` is the one already in the table. Both are in scope, which is what
makes the conditional form worth having:

```kotlin
.onConflict {
    onColumns("citizen_id")
    doUpdate(
        "tribe = excluded.tribe, assessed_at = excluded.assessed_at",
        whereCondition = "census.assessed_at < excluded.assessed_at"
    )
}
```

That writes only where the incoming assessment is newer than the stored one. Without it a late-arriving message
overwrites a later one, which is the ordinary failure of an upsert fed by a queue.

A `doUpdate` can also report what it overwrote, `RETURNING` having read the row both ways since PostgreSQL 18:

```kotlin
db.insertInto("census")
    .values(listOf("citizen_id", "tribe"))
    .onConflict {
        onColumns("citizen_id")
        doUpdate("tribe = excluded.tribe")
    }
    .returning("citizen_id", "old.tribe AS previous_tribe", "(old.citizen_id IS NULL) AS inserted")
    .fetchRowStrict("citizen_id" to id, "tribe" to tribe)
```

`old` is the row as it stood before this statement, `new` the row as it stands after, and a bare column name is
already `new`. A row that was *inserted* rather than updated has no old row at all, so every `old.` reference on
it is `NULL` — which is how the statement tells you whether it created the row or overwrote one, the thing
`ON CONFLICT` otherwise never says. It holds row by row where several rows go in at once — see
[Bulk Writes](../driver/bulk-writes.md#upserts) for that shape.

`returning` passes its list through unread like every other clause, so an expression with an alias sits there
as readily as a column name.

> **A `DO NOTHING` that conflicts returns no row**, whatever the `RETURNING` asks for — `old` included, there
> being no row in the result to carry an `old` for. The row exists and the statement still hands back nothing,
> because this statement did not write it.
>
> So `returning("id")` leaves the terminal with an empty result. `fetchFieldStrict<Int>()` raises
> `InvalidOperationException(INCORRECT_RESULT_SIZE)`, and `fetchField<Int>()` raises
> `MappingException(REQUIRED_ATTRIBUTE_MISSING)` — a non-nullable `T` counts no rows as a missing value.
> `fetchField<Int?>()` is the one that comes back rather than raising, but what it comes back with is `null`,
> which says "nothing was inserted" and is not the id of the row that is sitting there. Reach for it when that
> was the question; it is no use when the id was.
>
> **When you want the id either way, `doUpdate` is the answer** — an `UPDATE` happened, so `RETURNING` reports
> it. Where there is nothing worth updating, the idiom is to assign a column to itself:
> `doUpdate("citizen_id = excluded.citizen_id")`. That is a real write, not a no-op — the row is rewritten in
> place of being left alone, so an upsert run hot leaves dead tuples behind for rows that did not change. A
> `whereCondition` excluding the row puts you back in the empty-result case, for the same reason as
> `DO NOTHING`.
>
> The whole matrix is under
> [Nullability and the Strict variants](../driver/queries.md#nullability-and-the-strict-variants).

Bulk upserts are a different shape and belong to the driver: one `UNNEST` statement with `ON CONFLICT` over it
beats a loop of these by the round trips alone. See [Bulk Writes](../driver/bulk-writes.md#upserts).

## A Query Is a Value

Nothing is sent until a terminal is called, so a query can be built, passed around, rendered and copied.

**`toSql()`** renders what it would send. That makes it composable, because no query carries its own
parameters: the `@name` placeholders survive being embedded, and are bound by whoever runs the outer statement.

```kotlin
val recent = db.select("id", "province_id").from("edicts").where("issued_at > @since")

val counts = db.rawQuery(
    """
    WITH recent AS (${recent.toSql()})
    SELECT province_id, count(*) FROM recent GROUP BY province_id
    """
).fetchRows("since" to cutoff)
```

The same rendered query drops into a subquery or an arm of a `UNION` on the same terms. Rendering is not cached
and costs whatever assembling costs — nothing once per request, and not something to do per row.

**`copy()`** gives an independent builder with the same clauses, for variants off a shared base:

```kotlin
val base = db.select("id", "cognomen").from("senate")

val active   = base.copy().where("rank <> 'RETIRED'")
val retired  = base.copy().where("rank = 'RETIRED'")
```

`base` is untouched, and anything registered with `registerResultConverter` comes along with the copy.

## Raw SQL

`db.rawQuery(sql)` is a `RunnableQuery` like any other: same terminal family, same session handling, same
`toSql()`, and it can be a plan step.

The difference is not reach. Every builder clause is passed-through SQL, `recursive()` is on all four of them,
and a window function or a `DISTINCT ON` goes in `select` — there is not much the builders cannot say. Reach
for `rawQuery` when you would rather write the statement whole: because it already exists, because it came from
somewhere else, or because assembling it a clause at a time buys nothing.

The builders cover four statements, so anything that is a fifth arrives here by default — a `CALL` among them.
It is still an ordinary query read with an ordinary terminal, the `OUT` parameters coming back as the columns
of the single row it returns:

```kotlin
// CREATE PROCEDURE province_census(uid int, OUT population int)
val population = db.rawQuery("CALL province_census(@uid, NULL)").fetchFieldStrict<Int>("uid" to 7)
```

The `NULL` is PostgreSQL's rule rather than anything here — `CALL` wants a value in every argument position,
outbound ones included. See [Functions and Procedures](../driver/functions-procedures.md#out-and-inout-parameters-in-a-call).

It also has one terminal nothing else has:

```kotlin
db.rawQuery(
    """
    CREATE TABLE IF NOT EXISTS census (id SERIAL PRIMARY KEY, name TEXT);
    CREATE INDEX IF NOT EXISTS census_name ON census (name)
    """
).execute()
```

`execute()` speaks the **Simple Query Protocol**, which binds nothing: the SQL reaches the server exactly as
written, so an `@name` left in it arrives as literal text rather than as a parameter. That is why no builder
offers it — a builder always has values to bind — and why an `INSERT`, `UPDATE` or `DELETE` written by hand
belongs in `update()` instead. What it does accept is several statements separated by `;` in one round trip,
which PostgreSQL wraps in an implicit transaction. A statement that returned rows is refused, unless
`execute(ignoreRows = true)` says to drop them instead — which is what a script written elsewhere needs, a
`pg_dump` one emitting `SELECT pg_catalog.setval(...)` for every sequence. There is no reading those rows
from here either way.

## Per-Query Converters

The driver gives every query converter registries of its own, chained to the session's and thrown away with the
query. `registerResultConverter` and `registerParameterConverter` are how a builder reaches them:

```kotlin
val envelopes = db.select("payload").from("dispatches")
    .registerResultConverter(SealedEnvelopeConverter)
    .where("legion_id = @id")
    .fetchObjects<Envelope>("id" to 7)
```

A mapping that one report needs is registered for that report and nowhere else. Registering it on the type
manager instead would reach **every session pointing at that database**, this being a JVM-wide registry keyed
by the physical database — see [Type System](../driver/type-system.md#scope-a-session-handle-over-global-state).

Both return the builder's own type, so they can sit anywhere in the chain rather than having to come last, and
`copy()` carries them. Registered converters are consulted ahead of the session's, and a later registration
wins over an earlier one.

Two uses common enough to be written for you rather than by you:

- reading `dynamic_dto` payloads under a different `Json` — see
  [A Different `Json` for One Query](dynamic-dto.md#a-different-json-for-one-query);
- reading composites as maps, the whole subtree, whatever they are registered as — `compositesAsMaps()`, see
  [Reading Them All as Maps](../driver/composites-reflection.md#reading-them-all-as-maps).

## Next

- [Transactions and Failures](transactions-failures.md) — what happens around a query that goes wrong
- [Transaction Plans](plans.md) — when the sequence of queries is itself data
