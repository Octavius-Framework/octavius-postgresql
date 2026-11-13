# Concurrency and Virtual Threads

*A Roman road carried the traffic of an empire, and then narrowed to a bridge over a gorge that took one cart at a time.
The queue that formed there was not a failure of the road — it was the one place the width was fixed. A connection is
that bridge: everything around it scales, and the wire itself stays exactly one conversation wide.*

The driver is blocking: `fetchObjects<T>()` holds its thread until the rows are there, and there is no async API beside
it. It is built for virtual threads, on which a blocked call parks instead of holding a platform thread. How much runs at
once is then set by connections rather than threads, and a session runs one statement at a time.

Contents:
* [One session runs one statement at a time](#one-session-runs-one-statement-at-a-time)
* [Running on virtual threads](#running-on-virtual-threads)
* [What is shared beyond the connection](#what-is-shared-beyond-the-connection)
* [What can cross a thread boundary](#what-can-cross-a-thread-boundary)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

## One session runs one statement at a time

A PostgreSQL connection runs one statement at a time, and a session is one connection. Sharing a session between
threads is safe, since nothing interleaves and no result reaches the wrong caller, but it is not parallel: a call from a
second thread waits for the first to finish. How long depends on what the session is doing:

| While the session is running… | A call from another thread                                   |
|:------------------------------|:-------------------------------------------------------------|
| a query                       | waits until its result is read                               |
| a `forEach*` loop             | waits until the last row, time spent in your block included  |
| a listener loop               | waits until the loop is cancelled                            |
| a `COPY` transfer             | is refused with `InvalidOperationException(CONNECTION_BUSY)` |

`isValid(timeout)` waits its turn the same way, and its timeout never shortens the query it waits behind.

A call from the same thread while its own rows are still being read, from a `forEach*` block or from a converter under
a mapping fetch, is refused with `CONNECTION_BUSY` before anything is sent, and the connection stays usable.
[Queries](queries.md#do-not-re-enter-the-session-while-rows-are-being-read) lists which positions count.

## Running on virtual threads

Nothing in the driver is `synchronized`; every lock it takes is a `ReentrantLock`. On JDK 21 to 23 a virtual thread
that blocks inside `synchronized` pins its carrier thread for the whole wait, while one blocked in the driver unmounts
and leaves its carrier free.

`OctaviusDispatchers`, in `io.github.octaviusframework.driver.concurrent`, has two handles on one
`Executors.newVirtualThreadPerTaskExecutor()`, with no pool to size and nothing to shut down:

| Member                                | Type                  | For                                                          |
|:--------------------------------------|:----------------------|:-------------------------------------------------------------|
| `OctaviusDispatchers.Virtual`         | `CoroutineDispatcher` | Launching database work from coroutines                      |
| `OctaviusDispatchers.VirtualExecutor` | `ExecutorService`     | The same for Java-shaped APIs: `CompletableFuture`, `submit` |

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

Each coroutine borrows its own session and closes it at the end, so the eight queries run on eight connections at once.
Eight coroutines sharing one session would get the same results one after another.

The connections are the limit: as many statements run at once as the pool hands out connections, and a thousand
virtual threads against a pool of ten are a thousand tasks waiting for ten connections. `Dispatchers.IO` works too, but
it runs on a bounded set of platform threads, so its size is a limit of its own. `Dispatchers.Default` is sized for CPU
work, and blocking it starves everything else in the process. The listener loops take an optional dispatcher and use
`Virtual` when none is given.

## What is shared beyond the connection

The type catalog is shared by every session on a database across the JVM; what that means for registering is in
[Type System](type-system.md#scope-a-session-handle-over-global-state). Registering and `reloadTypes()` are safe while
other threads query: a change replaces the catalog whole, so no thread sees it half-applied, and reading the catalog
takes no locks.

Every terminal pins one catalog before it sends anything, and its whole run reads that one: the parameters it encodes,
the columns it decodes together with the values nested inside them, the converters it resolves and the `Row`s it
returns. A registration or reload reaches the terminals that start after it, never a statement already running or rows
already returned. The same holds for a converter registered on a query object: its next terminal uses it, rows from the
last one do not.

## What can cross a thread boundary

| Object                                  | Safe to hand to another thread?                                                                     |
|:----------------------------------------|:----------------------------------------------------------------------------------------------------|
| A value already pulled out with `get`   | Yes. It is an ordinary object with nothing tying it to the driver.                                  |
| `Row`, `List<Row>`                      | Yes. A row is decoded when it is built, and `get<T>()` converts against the catalog its run pinned. |
| `OctaviusSession`                       | Yes, one statement at a time; see [above](#one-session-runs-one-statement-at-a-time).               |
| A query object (`createNativeQuery(…)`) | Yes, with its converters registered before it is handed over. It runs on its session.               |

## Practical rules and gotchas

* **One session per unit of work.** Sharing one across threads is safe and buys nothing; a session per task is what
  runs the work at once.
* **Give listeners and `COPY` transfers their own session.** A listener holds the session until it is cancelled, and a
  `COPY` refuses everything else until it ends.
* **Do not query a session while its rows are being read.** From a `forEach*` block or a converter that is refused
  with `CONNECTION_BUSY`; use a second session.
* **Do slow work after a `forEach*` loop, not in it.** The block runs while the connection is held, so a web call per
  row keeps one of the pool's connections for the whole loop.
* **The pool size is your concurrency.** Virtual threads remove the thread limit, not the connection limit.
* **`Virtual` for driver work, `IO` if you prefer, never `Default`.**
* **Register types once, at startup.** A registration or reload is safe under load, but reaches only the terminals
  that start after it.
* **For a deadline, prefer `statement_timeout` to `cancelQuery()`.** A cancel names the connection, not the statement,
  so from another thread it can land on whatever the session runs next; see
  [Queries](queries.md#cancelling-a-query-in-flight).
