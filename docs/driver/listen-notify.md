# PostgreSQL Listen / Notify

*The cursus publicus kept riders and fresh horses stationed along every imperial road, so a dispatch from the frontier
reached Rome in days instead of months. Nothing was ever delivered to an officer who was not at the station when the
rider came through. That is the shape of `LISTEN` / `NOTIFY`: channels are the roads, notifications are the dispatches,
your listener is the officer at the way-station — and only the sessions standing there at that moment are handed
anything.*

Octavius wraps PostgreSQL's `LISTEN` / `NOTIFY` in a coroutine-based API, so an application can react to database events as they happen rather than polling a table.

Everything lives behind `session.notifications`, an instance of `NotificationManager`.

Contents:
* [Subscribing and emitting](#subscribing-and-emitting)
* [When a notification is actually delivered](#when-a-notification-is-actually-delivered)
* [Receiving them](#receiving-them)
* [Listener loops](#listener-loops)
* [Practical rules](#practical-rules)

## Subscribing and emitting

```kotlin
// Register this connection on one or more channels
session.notifications.listen("senatus_curia", "province_updates")

// Drop one subscription, or all of them
session.notifications.unlisten("senatus_curia")
session.notifications.unlistenAll()

// Dispatch a message, with or without a text payload
session.notifications.notify("province_updates", "consul_elected=42")
session.notifications.notify("province_updates")
```

Subscriptions belong to the **connection**, not to your session object: `listen` registers the physical connection behind that session. Because that connection outlives the session when it came from a pool, closing a session that subscribed to anything issues an `UNLISTEN *` on the way out — otherwise the next borrower would inherit the registrations and start receiving your notifications. Sessions that never called `listen` pay nothing for this.

That cleanup only covers subscriptions made through `listen`. A hand-written `LISTEN` sent as an ordinary query is invisible to it and stays on the connection — one instance of a [general rule about connection state](initialization.md#what-survives-a-return-to-the-pool). Nor can any of it survive the connection itself: a dropped or replaced connection loses its subscriptions silently, so anything long-lived has to re-subscribe after reconnecting.

Channel names are quoted as PostgreSQL identifiers, so a name with capitals or spaces survives intact and means the same thing on both `listen` and `notify`. Watch out only when something *else* emits the notification with hand-written SQL: `NOTIFY MyChannel` without quotes is folded to `mychannel` by PostgreSQL and will not reach a listener registered on `MyChannel`. Payloads are bound as parameters, never spliced into SQL.

## When a notification is actually delivered

Four rules decide whether a dispatch reaches you, and all four are PostgreSQL's:

**Only on commit.** A `notify` issued inside a transaction is held until that transaction commits. Verified: with the notifier sitting in an open transaction, the listener saw nothing; the moment the notifier committed, the message arrived. A rolled-back transaction sends nothing at all.

**Only between the listener's transactions.** A listening session inside a transaction is handed nothing until that transaction ends, however many queries it runs in the meantime; what was sent to it arrives once it commits or rolls back.

**Including back to the sender.** A session listening on a channel receives its own notifications too — it is not excluded.

**Payloads are limited to under 8000 bytes.** 7999 bytes go through; 8000 is refused by the server with a `DataException`. Send an identifier and let the receiver fetch the rest; a payload is a doorbell, not a delivery van. A `notify` without a payload arrives with `payload` as an empty string rather than null.

## Receiving them

Notifications surface as a `SharedFlow<PgNotification>`, collected like any other flow. Each carries the channel, the payload, and the backend process id of the sender:

```kotlin
coroutineScope.launch {
    session.notifications.messages.collect { notification ->
        println("Channel: ${notification.channel}")
        println("Payload: ${notification.payload}")
        println("Sent by backend PID: ${notification.processId}")
    }
}
```

> [!WARNING]
> **The flow has no replay: a notification that arrives while nothing is collecting is gone.** It is not queued for a collector that shows up later. Start collecting *before* you subscribe, or at least before anything can notify the channel — otherwise the first events, which are usually the ones that matter at startup, are silently lost.

Once there *is* a collector, the buffer sized by `notificationBufferCapacity` (default **256**) absorbs bursts. When it fills, the driver discards the oldest unprocessed notification to make room for the newest — a `DROP_OLDEST` strategy, so a slow collector loses history rather than blocking the connection. The capacity is set through the connection properties or the URL (`?notificationBufferCapacity=N`).

### Notifications ride along with anything the connection reads

The driver picks up notifications while reading *any* message from the server, which has a practical consequence worth knowing: **a session that keeps running queries needs no listener loop at all.** A plain `SELECT` on the listening session delivers whatever has arrived in the meantime.

A listener loop is what you need when the connection would otherwise sit idle — nobody is reading the socket, so nothing reaches the flow.

## Listener loops

Both are suspending functions; launch one in a coroutine after subscribing.

Neither starts while a transaction is open on the connection: a loop holds the connection, so nothing could end that transaction, and nothing would ever arrive. Both throw `InvalidOperationException(CONNECTION_BUSY)` instead and leave the session as it was — commit or roll back first. A session with auto-commit off that has run nothing since is not in a transaction yet as far as the server is concerned, and its loop receives as usual.

> [!IMPORTANT]
> A running loop **holds the connection for its entire duration.** A query issued on that same session from another thread blocks until the loop is canceled — measured at over two seconds against a loop that was simply left running. Give a listener its own session; do not share it with query traffic.

### Polling loop

```kotlin
val job = launch { session.notifications.startPollingListenerLoop(pollTimeoutMs = 500) }
// ...
job.cancelAndJoin()
```

The sentry doing rounds: it waits on the socket with a timeout and tries again, so it can notice cancellation between attempts.

- **Graceful cancellation** — the loop simply ends, the connection stays open and in sync.
- **Reusable** — the session can go on to run queries, or return to a pool.
- **Best for** pooled or shared connections, and anywhere the connection must survive the listener.

### Interruptible loop

```kotlin
val job = launch { session.notifications.startInterruptibleListenerLoop() }
```

The sentry standing fixed watch: it blocks on the socket read indefinitely and never wakes up on its own.

- **Hard cancellation** — the blocking read cannot be interrupted politely, so cancelling forces the driver to abort the physical connection to break out of it.
- **Not reusable** — that connection is finished afterwards, by design.
- **Best for** dedicated long-lived workers, where polling is not worth paying for and throwing the connection away at shutdown costs nothing.

## Practical rules

* **Give the listener its own session.** It holds the connection while it runs, and the interruptible variant destroys it on shutdown.
* **Collect before you subscribe.** No collector means no delivery, with nothing queued and no error.
* **Treat the payload as a pointer.** Under 8000 bytes, and the receiver should fetch the real data itself — the payload cannot be trusted to carry state that matters.
* **Expect duplicates and gaps.** A dropped connection loses its subscription silently, and the buffer discards the oldest entries under load. `LISTEN`/`NOTIFY` is a hint that something changed, not a durable queue.
* **Re-subscribe after reconnecting.** Subscriptions live on the connection, and a session that borrows a different physical connection is listening to nothing until it says so again.
