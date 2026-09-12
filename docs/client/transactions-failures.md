# Transactions and Failures

*Before any public act a magistrate took the auspices. An unfavourable sign meant the business could not go
forward — but it stopped nothing by itself. Somebody had to declare it. Business pushed through after a bad
sign was `vitio`: flawed, and annullable later, while looking for all the world like a thing that had been
done.*

Two questions, and one page because the interesting part is where they meet. A transaction commits or unwinds.
A failure is thrown or handed back as a value. What binds them is that **a failure turned into a value stops
being a throw** — and a transaction that only rolls back on a throw carries on past one as though nothing had
happened.

> Savepoints, manual `begin`/`commit`, transaction state and the isolation levels themselves are the driver's
> and are unchanged here. See [Transaction Management](../driver/transactions.md).

## Which Session Am I On

The driver is session-per-connection: an operation needs a session, so a function that does one takes a session
parameter, and so does every function that calls it. An application is not shaped that way.

```kotlin
fun findSenators(provinceId: Int): List<Senator> =
    db.select("id", "cognomen", "province_id").from("senate").where("province_id = @p")
        .fetchObjects("p" to provinceId)
```

Called on its own, that borrows a session for the call and gives it straight back — which costs no round trip
unless the session registered a `LISTEN` or left a transaction open. Called from inside `db.transaction { }` on
the same thread, it lands on the transaction's session and commits and rolls back with it. It does not know
which, and does not need to.

That binding is **per thread**. Work handed to another one does not inherit it: a coroutine launched on a
different dispatcher inside the block gets a session of its own and a transaction of its own, and the commit
outside says nothing about it. What a session can and cannot be shared with is the driver's subject rather than
this page's — see [Concurrency](../driver/concurrency.md#what-can-cross-a-thread-boundary).

### One Session per Thread, Not One per Level

Nesting does not multiply connections. The session `execute` borrows is bound for as long as that call lasts,
so a client call made from inside another lands on the same one — transaction or no transaction:

```kotlin
db.execute {
    db.select("id").from("senate").fetchFields<Int>()   // runs on the session this block already holds
}
```

Nothing is given up by sharing, because a connection carries one exchange at a time regardless: the levels are
sequential whether they share or not. What it buys is that a pool of `N` serves `N` callers rather than `N / D`
at nesting depth `D`, and that nesting inside a pool of one is not a deadlock against itself — the outer level
holding the only connection while the inner one waits for a second that can never come.

Composing functions that each do a query is the ordinary way to use this client, so this is not an edge case;
it is what the binding is for.

The one exception is `REQUIRES_NEW`, and it is not an oversight. A transaction that has to survive the failure
of the one around it cannot share that one's connection — a connection carries one transaction — so it takes a
second deliberately. [Propagation](#propagation) says what that costs.

> [!NOTE]
> That is `DefaultSessionProvider`'s behaviour — what `fromDataSource` gives you. A provider written against a
> framework shares whatever that framework binds, which for Spring means inside `@Transactional` and not
> necessarily outside it.

## Querying From Inside a Result

Sharing has one limit, and it is the driver's. Some of your code runs *while a result is still being read* — a
`forEach*` block, and a `ResultConverter` under any mapping fetch — and there the level above has not finished
with the connection. The driver's rule for that position is
[do not re-enter the session](../driver/queries.md#do-not-re-enter-the-session-while-rows-are-being-read):

```kotlin
db.select("label").from("census").forEachRow(fetchSize = 500) { row ->
    db.select("count(*)").from("census").fetchFieldStrict<Long>()   // ← refused
}
```

`InvalidOperationException(CONNECTION_BUSY)`, on the first nested call, and the walk stops there. It is refused
**before anything reaches the wire**: nothing is corrupted, the connection stays healthy, the next statement
works normally, and nothing is left checked out.

The refusal is the same whether or not a transaction is open, and that uniformity is the point. The
alternative — a client that quietly finds a second connection in this one position — turns a mistake here into
a pool problem that surfaces somewhere else entirely, under load, as a timeout about connecting.

Two ways out, both of them ordinary:

* **Collect first, query after.** `fetchRows()` into a list and do the nested work outside the walk — the
  exchange is over by then, and it is the right answer whenever the result is one you can hold.
* **Keep the block to the row.** Writing a file, pushing to a queue, folding a total: none of that touches the
  session, and streaming exists for results too large to collect.

If the nested work genuinely needs a second connection while the first is mid-result, take one deliberately out
of the `DataSource` rather than leaving the client to guess:

```kotlin
dataSource.getOctaviusSession().use { second -> … }
```

## Propagation

```kotlin
db.transaction(propagation = TransactionPropagation.REQUIRES_NEW) { … }
```

| Mode           | Inside an existing transaction                                                                                  |
|----------------|-----------------------------------------------------------------------------------------------------------------|
| `REQUIRED`     | Joins it. The inner block does not commit; the outermost one decides, and a failure anywhere takes it all down. |
| `REQUIRES_NEW` | Suspends it and runs on a session of its own. Neither can roll the other back.                                  |
| `NESTED`       | Runs inside a savepoint. A failure rolls back to it and leaves the surrounding transaction usable.              |

`REQUIRED` is the default and the one that makes a repository function composable.

`REQUIRES_NEW` is what an audit record wants — it has to survive the failure of the work that produced it — at
the price of holding two connections at once, and of the inner transaction not seeing the outer one's
uncommitted rows.

**Two connections at once means the pool has to have two.** Where it has not, the outer transaction holds its
connection while the inner one waits for a second that only the outer could release, and what ends the wait is
the pool's `connectionTimeout`: [`InitializationException(CONNECTION_UNAVAILABLE)`](../driver/exceptions.md#4-initializationexception),
an exception about connecting raised by code that was starting a transaction — its `details` carry the pool's
own census, which is the part that says so. This is the one place in the client where a connection per
level is the design rather than an accident, so it is the one place to size a pool for — work that goes
`REQUIRES_NEW` `D` levels deep needs `D` connections to itself.

`NESTED` is still the same transaction and the same connection, so the outer one failing later discards this
work anyway.

## Isolation, Read-Only and Timeouts

```kotlin
db.transaction(
    isolation = TransactionIsolationLevel.SERIALIZABLE,
    readOnly = true,
    statementTimeout = 5.seconds,
    transactionTimeout = 30.seconds
) { … }
```

**All four apply only where this call actually starts a transaction.** Joining one that is already running
cannot change the level it began at, and a timeout applied to somebody else's transaction would stand over the
rest of it after this block returned — so under `REQUIRED` inside an existing transaction, and under `NESTED`
on its savepoint path, they are ignored and a warning says which ones were dropped. `REQUIRES_NEW` is what to
reach for where the terms have to hold. A `db.transaction { }` written inside a `db.execute { }` *is* starting
one — `execute` binds a session, not a transaction — so there its terms apply in full.

**All four are scoped to the transaction and travel as one statement**, sent immediately after the `BEGIN`:
`SET TRANSACTION` for isolation and `readOnly`, `SET LOCAL` for the timeouts, in a single round trip. Nothing
is left on the connection when the transaction ends, so nothing follows it back into the pool.
`statementTimeout` bounds any one statement; `transactionTimeout` bounds how long the whole thing may stay
open. `null` leaves the server's own setting alone, and asking for none of them sends nothing at all.

The rule and the warning both live in the driver's `TransactionManager`, which this delegates to — see
[Terms for one transaction](../driver/transactions.md#terms-for-one-transaction).

## Thrown or Returned

Queries throw, the way the driver throws. That is what keeps them usable from a `try`/`catch` and from a Spring
`@Transactional` without either knowing this module exists.

Where a failure should be a value instead, the split is decided in one place, and it reads **the exception's
type and nothing finer** — never the `reason` enum inside it, which exists to say what happened in a log line
rather than to be branched on. Where a distinction is worth acting on, the driver states it as a type: a
routine that raised an error of its own and a routine whose own assertion failed are two classes rather than
two reasons on one, which is what lets them land on opposite sides of this table.

| Thrown — the calling code is wrong                 | Returned as `Failure` — the operation did not work out    |
|----------------------------------------------------|-----------------------------------------------------------|
| SQL the server would not parse                     | A violated constraint                                     |
| A row that does not fit the class it was asked for | A deadlock                                                |
| A type the registry has never heard of             | A serialization failure                                   |
| A value no codec would encode                      | A routine's `RAISE EXCEPTION` — a business rule saying no |
| An operation the session's state forbids           | A statement that ran out of time                          |
| A transaction whose state forbids the statement    | Anything the driver gains later                           |
| A routine's own assertion, falsified by the data   |                                                           |
| A session that could not be obtained at all        |                                                           |

Everything on the left is a defect rather than an outcome — most of it the same on every run, the rest for the
reasons given below — so a `Failure` branch would be a slower way of reaching a stack trace. Everything
unlisted goes right, deliberately including exception types added in future versions: a caller who reached for
a result boundary is already handling failures, and an unrecognised one arriving there costs nothing, where
the same one thrown past a boundary that was asked to catch it is the surprise.

**A `fetch*Strict` that found no row is thrown**, and that is the whole point of the suffix. `Strict` asserts
that exactly one row is there, so a run that finds none has falsified something the calling code claimed. The
same reading covers a non-nullable `T` over a `NULL`. Absence that is expected says so in the type instead —
`fetchRow` returns `Row?`, `fetchField<String?>` returns `null` — and neither raises.

**A routine's own assertion is on the left for the same reason.** `INTO STRICT` and `ASSERT` are that claim
written in PL/pgSQL rather than in Kotlin, so `RoutineAssertionException` is read as a defect in the routine.
A `RAISE EXCEPTION` is the opposite case and goes right: nothing was falsified there, the database declined on
purpose, and a business rule answering is exactly the kind of failure worth carrying as a value.

**A doomed transaction is on the left for a different reason.** PostgreSQL refuses everything after an error
inside a transaction until it is rolled back, so `TransactionStateException(IN_FAILED_TRANSACTION)` reaches you
only where an earlier failure was turned into a value and the work carried on regardless — the combination two
sections down. Throwing it is the loudest way of saying so, and the failure worth reading is the earlier one.

## Three Doors, Three Widths

```kotlin
// One query
val senators: DataResult<List<Senator>> =
    db.select("id", "cognomen").from("senate").asResult().fetchObjects<Senator>()

// Anything wider that is not a transaction
val report: DataResult<Report> = dbResult { buildReport() }

// A transaction
val outcome: DataResult<Int> = db.transactionResult { … }
```

`asResult()` switches one query to result-returning terminals, which is worth it once a builder has put four
calls in front of the terminal and wrapping the chain would mean indenting all of it. It is the same boundary,
only a different shape at the call site.

`DataResult` carries the driver's own `OctaviusException` rather than a parallel hierarchy, so a
`ConstraintViolationException` caught here is the one the driver raised, constraint name and query context
intact. It has `map`, `onSuccess`, `onFailure`, `getOrNull`, `getOrThrow` and `getOrElse`.

## The Combination That Misleads

```kotlin
// Wrong
db.transaction {
    val result = dbResult { insertInto("edicts")… }   // the failure is caught here
    // …and the block ends normally, so the transaction goes on to its commit
}
```

A plain transaction rolls back on a throw and on nothing else. A failure caught into a value inside one is no
longer a throw, so the transaction finishes and goes on to commit — the auspice was taken, the sign was bad,
and somebody wrote it down instead of stopping.

What happens at the commit depends on where the failure came from. One the server raised — a constraint, a
deadlock, a `RAISE EXCEPTION`, the kind `dbResult` hands back as a value — has already doomed the transaction,
and PostgreSQL would carry the commit out as a rollback while reporting none. The driver refuses it instead,
with `InvalidOperationException(COMMIT_OF_FAILED_TRANSACTION)`: nothing is saved and nothing pretends
otherwise, but the failure surfaces at the end of the block rather than where it happened, and as the refusal
rather than as itself. One the server never saw — a `Failure` the block made itself and carried on past — dooms
nothing, and there the business stands: the transaction commits over it.

`transactionResult` is what closes that gap, and it is why the two are not left to composition:

```kotlin
val outcome = db.transactionResult {
    val id = insertInto("edicts").values(listOf("title")).returning("id")
        .asResult().fetchFieldStrict<Int>("title" to title)
    id.map { … }
}
```

A returned `Failure` rolls the transaction back and comes out as the value it already was; a `Success` commits.

## `SessionProvider`

Everything above is written against one interface, and it has two methods:

```kotlin
interface SessionProvider : AutoCloseable {
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T
    fun <T> transaction(definition: TransactionDefinition, block: () -> T): T
}
```

`DefaultSessionProvider` binds the transaction's session to the thread that started it. That is what
`fromDataSource` gives you, and it is right for the standalone case: a connection pool, and nothing else
deciding when transactions begin and end.

Where a framework owns transactions itself, implement this against it and pass it to
`OctaviusClient.fromSessionProvider` — otherwise the two will each open one. For Spring that is under thirty
lines over the driver's existing `OctaviusTemplate` and a `PlatformTransactionManager`:

```kotlin
class SpringSessionProvider(
    private val template: OctaviusTemplate,
    private val transactionManager: PlatformTransactionManager
) : SessionProvider {

    override fun <T> execute(action: OctaviusSessionOperations.() -> T): T = template.execute(action)

    override fun <T> transaction(definition: TransactionDefinition, block: () -> T): T {
        val spring = DefaultTransactionDefinition().apply {
            propagationBehavior = when (definition.propagation) {
                TransactionPropagation.REQUIRED -> SpringDefinition.PROPAGATION_REQUIRED
                TransactionPropagation.REQUIRES_NEW -> SpringDefinition.PROPAGATION_REQUIRES_NEW
                TransactionPropagation.NESTED -> SpringDefinition.PROPAGATION_NESTED
            }
            definition.isolation?.let { isolationLevel = it.jdbcValue }
            isReadOnly = definition.readOnly
        }
        val timeouts = listOfNotNull(
            definition.statementTimeout?.let { "SET LOCAL statement_timeout = ${it.inWholeMilliseconds}" },
            definition.transactionTimeout?.let { "SET LOCAL transaction_timeout = ${it.inWholeMilliseconds}" }
        )

        return TransactionTemplate(transactionManager, spring).execute {
            if (timeouts.isNotEmpty()) {
                template.execute { createNativeQuery(timeouts.joinToString("; ")).execute() }
            }
            block()
        }
    }
}
```

`execute` is a single line of delegation, the template already taking the receiver-lambda the interface asks
for, and isolation maps straight across because `TransactionIsolationLevel.jdbcValue` *is* Spring's
`ISOLATION_*` constant. What is not delegation is the timeouts: Spring has one, it means the transaction rather
than the statement, and it enforces it itself — so both `SET LOCAL`s are yours to issue, and they account for
about a third of the class. They go in one statement for the same reason the driver's own `required` sends them
that way: a script separated by `;` is one round trip, and there is nothing here to bind.

That is the whole of what a `client-spring-integration` module would contain, which is why there is not one.
See [Spring Integration](../driver/spring-integration.md) for what the driver's own module wires up.

## Next

- [Transaction Plans](plans.md) — when the sequence itself has to be data
- [Queries](queries.md) — the builders these transactions wrap
