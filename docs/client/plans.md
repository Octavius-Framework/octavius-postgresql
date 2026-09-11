# Transaction Plans

*A Roman will was written to be carried out by somebody who was not in the room when it was written. The
clauses were read in order, and a later one leaned on an earlier one having taken effect: an heir had to accept
before the legacies charged on him meant anything. The testator did not execute it. He wrote down what was to
happen, and handed that over.*

A `transaction { }` block is a lambda. A plan is a **value**: it can be built up, counted,
[inspected](#reading-one-that-went-wrong), merged, handed on, and run somewhere that knows nothing about what
went into it.

## When a Block Is Not Enough

A block and a plan write the same things — [a graph](#writing-a-graph) whose later rows need keys the earlier
ones generate, [a record that may be new or may be edited](#creating-or-editing), the rows around it — and with
the same branches, since those come from the data rather than from the tool. What differs is when the work
exists.

A block runs as it is decided. Its values are plain Kotlin locals, it can read inside the transaction and
decide on what it read, and a repository function called from inside it joins its transaction without being
told.

A plan exists before any of it runs, and that is what it is for:

- **Settled before it starts.** Every step is decided and every statement rendered before the transaction
  opens, so a malformed plan — an `UPDATE` without its `WHERE`, a handle out of order — is
  [refused before any of it runs](#checked-before-it-runs).
- **A value.** The function that knows the tables can [hand its part back](#returning-a-fragment) instead of
  doing it, and the caller merges it with other work, [describes it](#reading-one-that-went-wrong),
  [runs it again](#running-one-twice), or runs it [inside a block](#inside-a-block) of its own.
- **Handed, not found.** A helper adding to a plan takes the plan as a parameter, where one called inside a
  block finds the transaction on its thread — the same work, with the dependency in the signature.

What it gives up is deciding as it goes. Its shape is fixed before it runs: a step can use what an earlier one
produced, but whether a step runs at all is settled from what was known beforehand. Until then an id is a
`TransactionValue` rather than an `Int`, and afterwards it is reached through the handle of the step that
produced it.

## Writing a Graph

In a block the parent's id is a local by the time the children need it. In a plan nothing has run yet, so there
is no id to pass — a handle stands in for it, the way an ORM lets a new child point at its parent's *object*
before the parent has one. `edictId.value()` below is the id the edict will have: usable by any step added
after it, resolved when that step runs, and the statements, and the order they run in, are the ones you wrote.

```kotlin
fun planEdict(draft: EdictDraft): Pair<TransactionPlan, StepHandle<Int>> {
    val plan = TransactionPlan()

    val edictId = plan.add(
        db.insertInto("edicts").values(listOf("title", "tribute")).returning("id")
            .asStep().fetchFieldStrict<Int>("title" to draft.title, "tribute" to draft.tribute)
    )

    if (draft.levy.isNotEmpty()) {
        plan.add(
            db.rawQuery(
                """
                INSERT INTO edict_items (edict_id, province, amount)
                SELECT @edict_id, u.province, u.amount
                FROM UNNEST(@provinces::text[], @amounts::int[]) AS u(province, amount)
                """
            ).asStep().update(
                "edict_id" to edictId.value(),
                "provinces" to draft.levy.map { it.province },
                "amounts" to draft.levy.map { it.amount }
            )
        )
    }

    return plan to edictId
}

val (plan, edictId) = planEdict(draft)
val id = db.executeTransactionPlan(plan)[edictId]
```

**Two steps, however many items.** The items go in as one statement, the rows turned sideways into one array
per column — [the bulk-write form](../driver/bulk-writes.md#inserting), one round trip where a step per item
would cost one per item. A plan's size should follow the tables a graph touches rather than the rows in them;
a loop belongs around what cannot share a statement, such as a parent per iteration whose children need its
key.

The items step is added only where there are items. Two empty lists carry no element type to send them as —
see [Empty batches need a type](../driver/bulk-writes.md#empty-batches-need-a-type) — and a plan being data, a
step with nothing to do can simply be left out of it.

`asStep()` turns any query into a step builder. Its terminals are the `fetch*` family and `update` — the
`forEach*` family is absent on purpose, a plan keeping every result so that later steps can use it, and a walk
over rows too large to hold having nothing to keep. So is `RawQuery.execute()`, which speaks a protocol that
binds nothing and could therefore take no reference to an earlier step.

## Creating or Editing

The same save often has to create a record or edit one that is already there, and both forms write it as one
path with the same branches: an insert or an update, and a dependent row kept, added, removed or never there. A
block has the id as a local from the first statement:

```kotlin
fun save(edict: Edict, sealExists: Boolean): Int = db.transaction {
    val row = mapOf("title" to edict.title, "tribute" to edict.tribute)

    val edictId = when (val id = edict.id) {
        null -> insertInto("edicts").values(row).returning("id").fetchFieldStrict<Int>(row)
        else -> {
            update("edicts").setValues(row).where("id = @id").update(row + ("id" to id))
            id
        }
    }

    // Kept, added, removed, or never there - the last runs nothing
    val sealedBy = edict.sealedBy
    when {
        sealExists && sealedBy != null ->
            update("edict_seals").setValues(listOf("sealed_by")).where("edict_id = @edict_id")
                .update("sealed_by" to sealedBy, "edict_id" to edictId)
        sealedBy != null ->
            insertInto("edict_seals").values(listOf("edict_id", "sealed_by"))
                .update("edict_id" to edictId, "sealed_by" to sealedBy)
        sealExists ->
            deleteFrom("edict_seals").where("edict_id = @edict_id").update("edict_id" to edictId)
    }

    edictId
}
```

A plan has no id yet when it is built: it is either one that is already there or one an insert will generate.
A `TransactionValue` can be either — `toTransactionValue()` wraps the known one, a handle's `value()` stands for
the generated one — and every step after binds it without knowing which it got:

```kotlin
fun planSave(edict: Edict, sealExists: Boolean): TransactionPlan {
    val plan = TransactionPlan()
    val row = mapOf("title" to edict.title, "tribute" to edict.tribute)

    val edictId: TransactionValue<Int> = when (val id = edict.id) {
        null -> plan.add(
            db.insertInto("edicts").values(row).returning("id")
                .asStep().fetchFieldStrict<Int>(row)
        ).value()

        else -> {
            plan.add(
                db.update("edicts").setValues(row).where("id = @id")
                    .asStep().update(row + ("id" to id))
            )
            id.toTransactionValue()
        }
    }

    // Kept, added, removed, or never there - the last adds no step
    val sealedBy = edict.sealedBy
    when {
        sealExists && sealedBy != null -> plan.add(
            db.update("edict_seals").setValues(listOf("sealed_by")).where("edict_id = @edict_id")
                .asStep().update("sealed_by" to sealedBy, "edict_id" to edictId)
        )
        sealedBy != null -> plan.add(
            db.insertInto("edict_seals").values(listOf("edict_id", "sealed_by"))
                .asStep().update("edict_id" to edictId, "sealed_by" to sealedBy)
        )
        sealExists -> plan.add(
            db.deleteFrom("edict_seals").where("edict_id = @edict_id")
                .asStep().update("edict_id" to edictId)
        )
    }

    return plan
}
```

The branches are the same two `when`s, and the plan is the longer of the two by its `plan.add`, `asStep()` and
the wrapping of the id. The block has two things the plan has not: the id comes back as its result, where the
plan reaches it only through the insert's handle, which exists on one branch of the two; and `sealExists` could
be read inside the transaction — a `SELECT … FOR UPDATE` on the seal — instead of taken from whoever loaded the
edict. What the plan has in return is [what a plan is for](#when-a-block-is-not-enough): none of it runs until
all of it is decided, nothing inside it can catch a step's failure and carry on to the next, and the whole of it
is a value — run on its own, handed back to a caller that merges it, or run again:

```kotlin
val saved = dbResult { db.executeTransactionPlan(planSave(edict, sealExists)) }
```

## Returning a Fragment

`planEdict` and `planSave` hand their plans back instead of running them, and that is the whole of the
pattern. A fragment that needs a value from another takes it as a `TransactionValue` — what `value()` returns —
and puts it among its parameters like any other:

```kotlin
fun planAudit(entityId: TransactionValue<Int>, summary: String): TransactionPlan =
    TransactionPlan().apply {
        add(
            db.insertInto("audit").values(listOf("entity_id", "summary"))
                .asStep().update("entity_id" to entityId, "summary" to summary)
        )
    }

val (edictPlan, edictId) = planEdict(draft)
edictPlan.addPlan(planAudit(edictId.value(), "edict issued"))

val id = db.executeTransactionPlan(edictPlan)[edictId]
```

`addPlan` appends the other plan's steps, in their order, after the ones already there. Handles either plan
handed out keep working, against the merged plan and against its result: a result is filed under the handle
itself rather than under a position, so where a step ends up in the merged sequence changes nothing about how
it is referred to.

**Merge the fragment that produces a value ahead of the one that uses it.** Inside one plan the order cannot
come out wrong, a handle only ever naming a step already added. Across two it can, and the other way round is
[refused before any of it runs](#checked-before-it-runs), naming both steps.

The plan merged in is not consumed and not changed: it can still be run on its own, or merged elsewhere.
Merging the same plan twice is refused — directly, or through two plans that both hold it. Its steps would run
twice under one handle, and only the last result of each would be reachable.

## Inside a Block

`executeTransactionPlan` opens its transaction through `transaction`, and takes the same
[propagation](transactions-failures.md#propagation). Under the default, `REQUIRED`, a plan run inside a block
joins the block's transaction — so the two are not alternatives. The block reads, locks and decides, in plain
Kotlin; the plan writes what was decided.

```kotlin
fun answer(petitionId: Int, draft: EdictDraft): Int = db.transaction {
    val status = select("status").from("petitions").where("id = @id").forUpdate()
        .fetchFieldStrict<String>("id" to petitionId)
    if (status != "OPEN") throw PetitionClosedException(petitionId)

    val (edictPlan, edictId) = planEdict(draft)
    val id = executeTransactionPlan(edictPlan)[edictId]

    rawQuery("UPDATE petitions SET status = 'ANSWERED', edict_id = @edict WHERE id = @id")
        .update("edict" to id, "id" to petitionId)
    id
}
```

The lock the first statement takes holds until the block ends, and the edict, its items and the answered
petition commit together or not at all. `planEdict` does not know it ran inside somebody else's transaction —
the same arrangement that makes a repository function composable, with a plan as one more thing that joins.

| Propagation    | A plan run inside a block                                                                                |
|----------------|----------------------------------------------------------------------------------------------------------|
| `REQUIRED`     | Joins the block's transaction, and commits or rolls back with it.                                        |
| `NESTED`       | Runs in a savepoint. A failure rolls the plan back to it and still throws; caught, the block carries on. |
| `REQUIRES_NEW` | Runs on a session of its own and commits on its own. It cannot see the block's uncommitted rows.         |

**Joined, a plan is all-or-nothing only together with the block.** Its failure is the block's failure, and
catching it inside the block — a `try`, a `dbResult` — does not give the plan a boundary of its own. A step the
server refused has doomed the transaction: PostgreSQL refuses every statement after it until the rollback. A
step that failed on this side of the wire — in a `map`, in mapping its result, on a strict fetch that found no
row — has left the steps before it in place, and a block that carries on commits them. Where the block has to
survive the plan failing, `NESTED` gives the plan its boundary back: whatever fails in it rolls back to the
savepoint, taking all of the plan and nothing the block did before it.

What `executeTransactionPlan` is given for isolation, read-only and the timeouts reaches only a transaction it
opens itself. Joined, or on `NESTED`'s savepoint path, the terms already in force stand, and a warning names
what was dropped — see [Isolation, Read-Only and Timeouts](transactions-failures.md#isolation-read-only-and-timeouts).

A retry goes around whoever opened the transaction, which for a joined plan is the block — see
[Running One Twice](#running-one-twice).

## Handles and What They Reach

`plan.add` returns a `StepHandle`. It is identity and nothing else — two handles are the same handle or they
are not — and it is useful only inside the plan whose `add` returned it, or a plan that one is merged into.

A handle reaches one thing, `value()`: the step's result, whole, as its terminal produced it. The type comes
with it, so reaching *into* a result is ordinary Kotlin, written in `map { }`:

```kotlin
"edict_id" to edict.value().map { it.get<Int>("id") }                      // one column of a fetchRowStrict
"name"     to items.value().map { rows -> rows[2].get<String>("name") }    // one column of one row of many
"ids"      to items.value().map { rows -> rows.map { it.get<Int>("id") } } // one column of every row
```

The third asks for the column at `Int`, so what comes out is a `List<Int>`, which binds as an array. Where
only one column is wanted at all, the terminal says so — `fetchFields<Int>()` and `value()`, with no lambda in
it.

Anything that is not a `TransactionValue` is passed through untouched, so an ordinary parameter map needs no
wrapping: only the values that depend on an earlier step do.

## What Binds and What Does Not

`value()` hands on whatever the terminal produced. Whether the next step can then *bind* it is a separate
question, answered by whether the driver can send that class as a parameter.

| The step's terminal                       | `value()` binds                             |
|-------------------------------------------|---------------------------------------------|
| `fetchField`, `fetchFieldStrict`          | ✅                                           |
| `fetchFields`, `fetchObjects`             | ✅ — a list of scalars binds as an array     |
| `update`                                  | ✅ — the affected-row count                  |
| `fetchObject`, `fetchObjectStrict`        | only if the class is a registered composite |
| `fetchObject*<Map<String, Any?>>`         | ❌ — nothing sends a Map; `spread()` does    |
| `fetchRow`, `fetchRowStrict`, `fetchRows` | ❌ — a `Row` is not something to send        |
| `fetchRow` that matched nothing           | ✅ — binds as `NULL`                         |

A `value()` nothing can send fails where the parameter is encoded, naming the class. `map` is the way across:
it reaches the object and takes the part that can be sent.

## `map` and the Spread

`map { }` applies a function once the value resolves, so a handle on a row can become the one column of it the
next step wants, and a list can become its size, without adding a step just to compute it:

```kotlin
plan.add(
    db.insertInto("audit").values(listOf("summary"))
        .asStep().update("summary" to items.value().map { "granted to ${it.size} provinces" })
)
```

**A transformation is the one place a plan runs code you wrote.** Anything it throws arrives as a
`MappingException` naming the step, the parameter and which `map` of the chain it was, with what was actually
thrown as its cause — a bare `NumberFormatException` out of `map { it.toInt() }` would otherwise travel as
itself, past `dbResult` and `transactionResult`, which catch `OctaviusException` and nothing else:

```
Details: Step 1 of the plan, parameter 'amount': map #2 over step 0.map(#1) threw
         NumberFormatException: For input string: "Gallia"
```

`#2` is the second `map` written on that parameter. A lambda has no name to report and every `map` in a chain
shares the parameter it is on, so the number is the whole of what tells them apart.

An `OctaviusException` raised in there is **passed through as it was thrown** — which is what a `row.get` for
a column the row has not got raises, and what a query run inside the lambda would raise. Restating one would
cost the type you catch on, so what it picks up instead is those same three on its `path`, the only thing a
layer can add to an exception without replacing it:

```
Details: Column not found: tribute
PATH: step 1 -> parameter 'name' -> map #1
```

### The spread

`spread()` marks a value to become **parameters of its own** rather than one parameter. Its entries arrive
under their own keys, and the name it was filed under is dropped:

```kotlin
val original = plan.add(
    db.select("title", "tribute", "province").from("edicts").where("id = @id")
        .asStep().fetchObjectStrict<Map<String, Any?>>("id" to id)
)

plan.add(
    db.insertInto("edict_archive").values(listOf("title", "tribute", "province"))
        .asStep().update("anything" to original.value().spread())
)
```

`@title`, `@tribute` and `@province` are bound; `@anything` is not, and nothing binds it. That name is a
placeholder — a map of parameters needs a key — and it is the one parameter name in a step that means nothing.
This is what makes copying a row with a change or two a single step rather than one parameter per column.

**The map comes from a `fetchObject*` terminal**, which treats a row as a record like any other. That is
where the columns' type is chosen: `Map<String, Any?>` asks the result converters what each column is, and
anything narrower asks them for that instead. `fetchObject` returns `Map<String, Any?>?`, which does not
spread — what an absent row contributes is said in a `map` first, `map { it ?: emptyMap() }`.

The mark is on the parameter slot rather than on the value in it, so everything ordinary Kotlin does to a map
can still be done first:

```kotlin
"anything" to original.value().map { it - "id" + ("archived_at" to Instant.now()) }.spread()
```

`spread()` is the last thing written: what it returns is not a `TransactionValue`, so `map` cannot follow
it.

## Checked Before It Runs

A plan is validated before any of it runs rather than partway through it — and before its transaction is
opened, where it opens one:

- **Every step's SQL is rendered.** An `UPDATE` that never got its `WHERE` is refused naming the step, instead
  of surfacing once the steps before it have already done their work — which on a plan whose first eighteen
  steps are slow matters rather a lot.
- **Every parameter is walked for handles**, through however many `map { }` wrap them, and each has to name
  a step ahead of the one using it. A handle from a plan that was never merged in fails that, and so does one
  from a [fragment merged the wrong way round](#returning-a-fragment).

An empty plan returns an empty result without opening a transaction at all.

The cost is one extra `toSql()` per step, rendering not being cached. Against a transaction's round trips that
is nothing.

## Reading One That Went Wrong

**Every failure a step raises names that step**, on the `PATH:` line of the exception — resolving its
parameters, running its statement and mapping its result alike. `describe()` is what turns the number back
into a step:

```kotlin
println(plan.describe())
```

```
TransactionPlan, 2 steps

step 0
  SELECT id, name, amount
  FROM tv_probe
  WHERE id = 1

step 1
  INSERT INTO tv_sink (name, amount)
  VALUES (@name, @amount)
  @name   <- literal
  @amount <- step 0.map(#1).map(#2)
```

This is the piece a plan needs and a block does not. A block is read where it is written; a plan is assembled
by one layer and run by another, so the code holding it when it fails is usually not the code that decided
what went in — and a plan built in a loop has the same SQL and the same parameter names in all twenty of its
steps, which is why the query context on the exception cannot separate the third iteration from the
seventeenth and the step number can.

```
MESSAGE: CONSTRAINT_VIOLATION_EXCEPTION:UNIQUE_CONSTRAINT_VIOLATION
Details: Key (title)=(De Tributis) already exists.
PATH: step 17
```

**What a literal *is* is deliberately not shown.** The wiring is the part that cannot be read off the code
that assembled the plan; a bound value can, and printing one would put a `bytea` parameter or a column of
personal data into whatever the description was written to. The values a step actually ran with are on the
`queryContext` of what it threw, which is bounded for the purpose.

A step whose query cannot be rendered says so in place of its SQL rather than throwing — a plan holding one
is among the things worth describing, and the other nineteen steps still describe.

### Which step is step 2

A step's number, in a description and in a failure alike, is **where it sits in the plan being run**. A
handle's own `toString` is not: it carries the index the handle was *created* at, and after `addPlan` that is
no longer where its step is.

```kotlin
val source = tail.add(…)    // step 0 of tail
head.addPlan(tail)          // head had two steps of its own, so that step is now step 2

source.toString()           // StepHandle(step 0)        - the index it was created at
head.describe()             // @amount <- step 2.map(#1) - where it runs
```

So read the number off `describe()` or off the failure, and treat a handle's own as saying which plan it came
from rather than where it will run. That distinction is also why a handle from another plan is reported the
way it is: there, naming the plan it was created in is the whole point.

## Running One Twice

Executing a plan does not consume it. The steps are copied out and the results kept in a map of the run's own,
with nothing written back:

```kotlin
repeat(3) { attempt ->
    try {
        return db.executeTransactionPlan(plan)
    } catch (e: ConcurrencyException) {
        if (e.reason == ConcurrencyExceptionReason.UNKNOWN || attempt == 2) throw e
    }
}
```

Retrying a serialization failure or a deadlock is a plain loop rather than a rebuild — and each run resolves its
handles against its own results, so the second run reads what the second run produced.

The loop goes around the frame that owns the boundary and not further in. A retry has to restart the
**whole** transaction, only a new one getting a new snapshot — and `executeTransactionPlan` is that frame where
it opens the transaction itself. Where it [joined a block's](#inside-a-block), the frame is the block, and the
loop goes around that instead: retried inside it, the plan would run again in a transaction the server has
already refused. See [Catching at the Right Altitude](../driver/exceptions.md#catching-at-the-right-altitude)
for what the server does to a doomed transaction in the meantime.

## Next

- [Transactions and Failures](transactions-failures.md) — the propagation and timeout arguments a plan takes
- [Bulk Writes](../driver/bulk-writes.md) — the one statement a graph's rows go in as
- [Queries](queries.md) — what `asStep()` is called on
