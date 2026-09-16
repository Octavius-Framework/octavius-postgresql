# Concurrency and Virtual Threads

*A Roman road carried the traffic of an empire, and then narrowed to a bridge over a gorge that took one cart at a time.
The queue that formed there was not a failure of the road — it was the one place the width was fixed. A connection is
that bridge: everything around it scales, and the wire itself stays exactly one conversation wide.*

Octavius is a **blocking** driver by design. There is no reactive layer, no callback API, and no `CompletableFuture` on the query path — a `fetchObjects<T>()` occupies its thread until the rows are there. That sounds like a throughput ceiling and stopped being one with Java 21: a blocking call on a virtual thread parks and costs nothing while it waits, which buys the scalability an async API is usually reached for without the API.

Getting that for free depends on one property, and it is the reason it is worth stating up front: **nothing in the driver is `synchronized`.** Every lock it takes is a `ReentrantLock` — not a stylistic preference but the condition under which a virtual thread blocked in the driver releases its carrier thread instead of holding it.

Contents:
* [One connection is one wire](#one-connection-is-one-wire)
* [Two threads on one session](#two-threads-on-one-session)
* [Reentrancy is refused, not queued](#reentrancy-is-refused-not-queued)
* [Virtual threads and pinning](#virtual-threads-and-pinning)
* [`OctaviusDispatchers`](#octaviusdispatchers)
* [Where the concurrency limit actually is](#where-the-concurrency-limit-actually-is)
* [What is shared beyond the connection](#what-is-shared-beyond-the-connection)
* [What can cross a thread boundary](#what-can-cross-a-thread-boundary)
* [Cancelling a query in flight](#cancelling-a-query-in-flight)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## One connection is one wire

PostgreSQL's protocol is a conversation over a single socket: a statement is sent, its result is read to the end, and only then can the next one begin. That is the protocol's rule and every driver lives with it. Octavius enforces it with a `ReentrantLock` on the connection stream, taken for the whole of each exchange — from the first byte written to the `ReadyForQuery` that ends it.

So a session is **thread-safe but not parallel**. Sharing one is safe: nothing interleaves, nothing corrupts, no half-read result reaches the wrong caller. It is simply not faster than using it from one thread, because the lock serializes exactly the part you were hoping to overlap.

## Two threads on one session

The second thread waits. It does not fail, and it does not see a scrambled result — it blocks on the lock until the first exchange finishes, then runs normally.

That is worth knowing mostly because of how long the wait can be. Most exchanges are milliseconds, but two things on the session hold the lock far longer:

| Holding the lock                             | For how long                                                  |
|:---------------------------------------------|:--------------------------------------------------------------|
| An ordinary query                            | Until its result is fully read                                |
| A `forEach*` stream                          | Until the last batch — including time spent in *your* block   |
| An open `COPY`                               | Until `endCopy()` or a cancel                                 |
| `startPollingListenerLoop` / `Interruptible` | **Until the coroutine is cancelled** — indefinitely by design |

A listener loop is the extreme case: it owns the connection for its entire life, and a query issued on that same session from another thread simply waits for it — [measured at over two seconds](listen-notify.md#listener-loops) against a loop that was merely left running. Give a listener its own session.

`COPY` is the one that refuses rather than waits: with a transfer open, anything else on that session throws `InvalidOperationException(CONNECTION_BUSY)` instead of blocking behind it, because a copy is a mode the connection is in rather than a statement it is running. See [COPY](copy.md#practical-rules-and-gotchas).

One operation deliberately steps around the queue: `isValid(timeout)`, which pools call to probe a connection, takes the same lock — so a probe never shortens the deadline of a query already in flight on another thread and never kills a healthy connection with a read timeout it did not ask for.

## Reentrancy is refused, not queued

A `ReentrantLock` lets the *same* thread re-enter, which is exactly wrong here: a thread that is in the middle of reading a result and starts a second statement on the same connection would interleave two exchanges on one wire. The lock cannot catch it, so a separate flag does. The driver tracks whether an exchange is in progress and throws **before anything reaches the wire**:

```kotlin
session.createNativeQuery("SELECT id FROM senators").forEachRow(fetchSize = 100) { row ->
    // Same connection, inside an unfinished exchange
    session.createNativeQuery("UPDATE …").update()   // InvalidOperationException(CONNECTION_BUSY)
}
```

The same guard covers a `ResultConverter` that queries the session mid-conversion, and a `COPY` started from either position. Nothing is corrupted and the connection stays usable — only the operation you attempted is refused. [Queries](queries.md#do-not-re-enter-the-session-while-rows-are-being-read) covers the whole picture, including which positions count as "inside".

If code in that position needs the database, give it a second session.

## Virtual threads and pinning

A virtual thread that blocks normally *unmounts*: it releases the platform (carrier) thread, which goes off to run something else, and is remounted when the blocking call is ready to proceed. Millions of virtual threads can therefore sit blocked on database I/O across a handful of carriers.

The classic thing that breaks it is `synchronized`. On JDK 21 through 23, a virtual thread that blocks inside a `synchronized` block **pins** its carrier — the carrier is stuck for the duration, and a pool of them can be exhausted by a few slow queries. JDK 24 removed that limitation, but a library that wants to behave on 21 has to avoid monitors altogether.

Octavius does. There is not a single `synchronized` block or `@Synchronized` in the driver; the connection lock, the registry publication locks and everything else are `ReentrantLock`, which parks a virtual thread cleanly rather than pinning it. That is the concrete meaning of the README's "virtual threads without pinning" — not a tuning flag, just an absence.

## `OctaviusDispatchers`

Two ready-made handles for running driver work on virtual threads, in `io.github.octaviusframework.driver.concurrent`:

| Member                                | Type                  | For                                                             |
|:--------------------------------------|:----------------------|:----------------------------------------------------------------|
| `OctaviusDispatchers.Virtual`         | `CoroutineDispatcher` | Launching database work from coroutines.                        |
| `OctaviusDispatchers.VirtualExecutor` | `ExecutorService`     | The same, for Java-shaped APIs — `CompletableFuture`, `submit`. |

Both are global and backed by one `Executors.newVirtualThreadPerTaskExecutor()`, so there is no pool to size and nothing to shut down.

```kotlin
import io.github.octaviusframework.driver.concurrent.OctaviusDispatchers

val provinces = coroutineScope {
    (1..8).map { provinceId ->
        async(OctaviusDispatchers.Virtual) {
            dataSource.getOctaviusSession().use { session ->        // one session each
                session.createNativeQuery("SELECT * FROM senators WHERE province_id = $1")
                    .fetchObjects<Senator>(provinceId)
            }
        }
    }.awaitAll()
}
```

Note the shape: **a session per coroutine**, borrowed from the pool and closed at the end. That is what makes the eight queries genuinely concurrent — eight connections, eight wires. Eight coroutines sharing one session would produce the same results, one after another.

`Dispatchers.IO` also works and is not wrong — it is a bounded pool of platform threads, so the blocking calls hold real threads and the concurrency ceiling becomes that pool's size rather than your connection pool's. `Virtual` simply removes one ceiling from the stack. What you should not use is `Dispatchers.Default`: it is sized for CPU work and blocking it starves everything else in the process.

The listener loops take an optional dispatcher for the same reason and default to `Virtual` when it is left out.

## Where the concurrency limit actually is

Not in threads, and not in coroutines. **The number of connections in your pool is the number of statements that can be running at once**, and nothing above it changes that: a thousand virtual threads against a ten-connection pool are a thousand tasks waiting for ten wires.

Which is the intended arrangement rather than a problem — a database has a limit on useful concurrent work, usually well below a thousand, and the pool is where that limit belongs. Two consequences worth internalizing:

* **Raise `maximumPoolSize` to raise throughput, not thread count.** The usual guidance for PostgreSQL — a small multiple of the core count, not hundreds — applies here exactly as it does elsewhere.
* **The server has its own ceiling, and it is lower than people expect.** PostgreSQL ships with `max_connections = 100`, three of which are held back by `superuser_reserved_connections`, leaving ~97 for everybody. That is the budget for *every* pool against that server at once — each application instance, each background worker, every `psql` someone left open. Ten instances of a service with `maximumPoolSize = 20` do not get 200 connections; they exhaust the server at half strength and the rest fail with `FATAL: sorry, too many clients already`. Size the pool against that total, not against one process.
* **A slow block inside `forEach*` holds a connection, not just a thread.** Streaming a million rows and calling a web service per row occupies one of your ten wires for the whole run. Collect first, do the slow work after, or give that job its own session outside the shared pool.

## What is shared beyond the connection

The type system is the one piece of global state, and it is deliberately global: registries are cached per **host +
port + database** across the whole JVM, so every session on that database shares one catalog and one set of converters.
That is why registration is a startup step.

It is built for many readers and rare writers: the dictionaries, the converters and the composite and enum registrations
are one immutable `TypeCatalog`, republished whole through a single `@Volatile` field under a lock. A registration
builds the next catalog from the current one and publishes it with one write, so no registration is ever observed
half-applied, however many parts of the catalog it touches. **Reads take no locks at all** — every query, every row,
every conversion is lock-free on that path.

The practical rule that follows is not about safety but about cost: registering from many threads at runtime copies a
collection each time and makes every later lookup slower. Register once, at startup, from one thread.
See [Scope: a session handle over global state](type-system.md#scope-a-session-handle-over-global-state).

### What a reload does *not* promise

`reloadTypes()` is safe to call while other threads are querying, and an execution already under way is not disturbed by
it.

**Every terminal pins one catalog before it sends anything, and everything that run touches reads that one**: the
parameters it encodes, the columns it describes, the codecs that decode them, the converters it resolves, the composite
and enum registrations those converters consult, and the `Row`s it hands back. A reload landing halfway through cannot
leave a result mapped against two catalogs, and the next terminal on the same query object picks the new one up.

That reaches into the values, not just the columns. A composite, array, range or domain is decoded by looking a further
codec up per attribute, per element, per bound, and those lookups go through the dictionaries of the catalog the column
was pinned to — so an attribute nested three deep is decoded against the same catalog as the row it arrived in.

The normal shape does not exercise any of this, because the normal shape is DDL followed by a reload at a point where
the application is not querying — startup, a migration, a test fixture. Reload there and the question does not arise.

## What can cross a thread boundary

| Object                                  | Safe to hand to another thread?                                                                   |
|:----------------------------------------|:--------------------------------------------------------------------------------------------------|
| A value already pulled out with `get`   | **Yes, unreservedly.** An ordinary Kotlin object with nothing tying it back to the driver.        |
| `List<Row>`                             | Yes — the connection is out of the picture, and the catalog they map against is fixed; see below. |
| `OctaviusSession`                       | Yes, but serialized — see [above](#two-threads-on-one-session).                                   |
| A query object (`createNativeQuery(…)`) | Yes, though it executes against its session and inherits that serialization.                      |
| `CopyIn` / `CopyOut` handles            | Technically yes; pointless, since the transfer occupies the connection either way.                |
| `LargeObject` descriptors               | **No** — valid only inside the transaction that opened them.                                      |

The first two rows differ only in what is left to do. An extracted value is finished; a `Row` still has the conversion
inside `row.get<T>()` ahead of it. That conversion resolves against the catalog its execution pinned, which the `Row`
carries, so it answers the same whenever it is called and on whichever thread.

What follows, in odd corners rather than on any ordinary path:

* **A converter registered on a query object after its rows came back does not apply to those rows.**
  `query.registerResultConverter(…)` takes effect from the next terminal; rows already in hand were mapped against the
  catalog their own run pinned. Keeping a query around and reconfiguring it later is what makes that visible.
* **A concurrent `reloadTypes()` does not reach them either** — see [above](#what-a-reload-does-not-promise).

Fetching on one thread and processing on a pool of others is entirely fine — that is the ordinary case and none of this
interferes with it.

## Cancelling a query in flight

`session.cancelQuery()` **must be called from a different thread than the one running the query** — the running thread is blocked inside the exchange and will not reach the call. The cancel does not queue behind the connection lock: it opens a separate short-lived connection to the server and asks the backend to abandon its current statement, which is how PostgreSQL's cancellation works at the protocol level. That connection negotiates TLS under the session's own `sslmode`, and both its connect and its reads are bounded by [`cancelSignalTimeout`](initialization.md#network-and-limits), 10 seconds by default. The call then waits for the backend to close that connection — PostgreSQL's way of acknowledging a cancel — so it returns having been delivered rather than merely written.

```kotlin
val worker = OctaviusDispatchers.VirtualExecutor.submit {
    session.createNativeQuery("SELECT pg_sleep(30)").execute()
}
// From another thread, after deciding it has gone on long enough
session.cancelQuery()
```

The cancelled statement surfaces on the blocked thread as `ExecutionAbortedException(QUERY_CANCELED)` (SQLSTATE `57014`), the session survives, and the next statement works normally.

Two properties of `cancelQuery()` follow from being a best-effort protocol request rather than a command:

* **It never throws.** A cancel that could not be connected, could not be encrypted under a mode requiring it, or was never acknowledged fails silently and the query keeps running — and the return tells you none of that apart.
* **It can hit the wrong statement.** The request names a *backend*, not a statement: the server signals that connection to abandon whatever it happens to be running when the signal is handled. If the statement you meant finished first and the connection has already started another, the cancel lands on **that** one. Only an idle backend makes it a no-op. See below.

### Why a cancel can hit the wrong statement

This is PostgreSQL's design rather than the driver's, and no client can work around it: a cancel request carries a process id and a key, and no statement identifier. The server hands the signal to that backend, which abandons whatever it is executing at its next interrupt check. Which statement that is depends entirely on when the signal arrives. `pgjdbc` sends the same request and inherits the same window.

Serializing a session on one connection does not help either, because the hazard lives in the gap between statements rather than inside one:

1. Thread A is running statement 1 and holds the connection lock.
2. Thread B calls `cancelQuery()`. The request goes out over its own connection.
3. Statement 1 finishes on its own before the signal is handled. Thread A returns and releases the lock.
4. Anything waiting takes the lock and sends statement 2.
5. The backend handles the signal and cancels statement 2.

Thread A sees a perfectly normal result and whoever issued statement 2 gets an `ExecutionAbortedException(QUERY_CANCELED)` it did nothing to deserve. The same shape appears with a pooled connection if a cancel is fired at a session that is then returned and re-borrowed, which is a good reason not to cancel on the way out of anything.

Two ways to stay clear of it:

* **Prefer `statement_timeout`.** A server-side deadline is bound to the statement it was set for and cannot slide onto the next one. As a [startup parameter](initialization.md#startup-parameters) it applies to every statement on the connection; set per session, to a stretch of work. It also needs no second thread and no second connection.
* **If you do cancel, do not let the session move on underneath it.** Cancel a statement you know is still running, and let the thread that issued it observe the outcome before anything else is sent on that session. Fire-and-forget cancels into a session other threads are queuing on are exactly the case above.

## Practical rules and gotchas

* **One session per unit of work.** Sharing a session across threads is safe and buys nothing; borrowing one per task is
  what makes the work concurrent.
* **Give listeners and long `COPY` transfers their own session.** Both hold the connection lock for their entire
  duration, and everything else on that session waits behind them.
* **Never query the session from inside a `forEach*` block or a converter.** That is reentrancy, and it is refused with
  `CONNECTION_BUSY` — use a second session.
* **Pool size is your concurrency, not thread count.** Virtual threads remove the thread ceiling; the connection ceiling
  stays where you set it — and the server's own `max_connections`, 100 by default, caps every pool against it put
  together.
* **`Virtual` for driver work, `IO` if you prefer, never `Default`.** Blocking the CPU dispatcher starves the whole
  process.
* **Register types once, at startup, from one thread.** The registries are global, lock-free to read, and copy-on-write
  to update.
* **Write to the registries when nothing is querying.** Registration and `reloadTypes()` are safe under load and are not
  observed half-applied, but a statement already running keeps the catalog it pinned: what you registered reaches the
  terminals that start after it, not the one in flight.
* **`cancelQuery()` needs another thread, and aims at a connection rather than a statement.** It can land on whatever
  that connection is running by the time the signal arrives. `statement_timeout` cannot miss like that, and is the
  better tool for a deadline.
