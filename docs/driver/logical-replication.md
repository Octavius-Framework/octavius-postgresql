# Logical Replication

*From his consulship in 59 BC Caesar had the acta of the Senate and the people written up and published, and scribes
copied them out for those away from Rome. A governor in his province read what the city had decided in the order it
was decided, and knew Rome as it stood at the last copy he had read. A logical slot is that service, run by
PostgreSQL: it hands out what was committed, transaction by transaction and in commit order, never what was rolled
back, and it keeps every transaction you have not confirmed, so a reader that went away picks up after the last one it
confirmed, not wherever the database has got to by then.*

The driver reads logical replication slots. `startPgOutput` streams a slot of PostgreSQL's built-in `pgoutput` plugin
as a `ChangeStream`, over a replication connection of its own: the committed transactions of the tables you publish,
whose rows read as the rows of a query do, through the same type catalog and the same converters. It can create the
slot as well, and copy what the tables held before it. Everything here is in
`io.github.octaviusframework.driver.replication`.

Contents:
* [What the server needs](#what-the-server-needs)
* [Streaming committed transactions](#streaming-committed-transactions)
* [Slots](#slots)
* [Reading a transaction](#reading-a-transaction)
* [Changes](#changes)
* [Columns an update did not send](#columns-an-update-did-not-send)
* [Logical messages](#logical-messages)
* [Types and converters](#types-and-converters)
* [Acknowledging](#acknowledging)
* [Processing asynchronously](#processing-asynchronously)
* [Closing, and staying connected](#closing-and-staying-connected)
* [Starting from a copy of the table](#starting-from-a-copy-of-the-table)
* [Other output plugins](#other-output-plugins)
* [What it does not do](#what-it-does-not-do)

## What the server needs

* **`wal_level = logical` on PostgreSQL 18.** From PostgreSQL 19 `replica` is enough: the first logical slot turns
  logical decoding on.
* **A role with the `REPLICATION` attribute**, or a superuser.
* **A publication** naming the tables whose changes you want. It is made in an ordinary session, since the
  replication connection runs no SQL:

```sql
CREATE PUBLICATION curia_mirror FOR TABLE senator;
CREATE PUBLICATION senatus FOR TABLE senator, legion;
```

## Streaming committed transactions

```kotlin
startPgOutput(
    "jdbc:octavius://localhost:5432/curia",
    OctaviusProperties().apply {
        user = "tabularius"
        password = "acta_diurna"
    },
    slot = "curia_mirror",
    publications = listOf("curia_mirror")
).use { stream ->
    stream.forEachTransaction { transaction ->
        for (change in transaction.changes) when (change) {
            is Change.Insert -> println("Elected: ${change.new.get<String>("cognomen")}")
            is Change.Update -> println("Changed: senator ${change.new.get<Int>("id")}")
            is Change.Delete -> println("Gone: senator ${change.old.get<Int>("id")}")
            is Change.Truncate -> println("The senate is empty")
        }
    }
}
```

It takes the URL and the properties a session takes, and logs in as a session does: TLS, authentication, the choice
among several listed servers. The connection is the stream's own, though. It runs no SQL, so publications are made and
slots dropped through an ordinary session, it never comes from a pool, and it closes with the stream.

`forEachTransaction` holds its thread until the stream ends, so a reader usually gets a thread of its own and is
ended from another one; see [Closing, and staying connected](#closing-and-staying-connected).

| Parameter        | Default         | Meaning                                                                                              |
|:-----------------|:----------------|:-----------------------------------------------------------------------------------------------------|
| `slot`           |                 | A slot made with `pgoutput`                                                                          |
| `publications`   |                 | The publications whose tables' changes come; at least one                                            |
| `newSlot`        | `null`          | A [slot to create](#slots) before the stream starts, which then takes no `startLsn`                  |
| `startLsn`       | `PgLsn.INVALID` | Where to start, unless the slot was acknowledged past it; `INVALID` starts where it was acknowledged |
| `messages`       | `false`         | Whether a transaction's [logical messages](#logical-messages) come                                   |
| `statusInterval` | 10 seconds      | How often the server hears what was received and acknowledged                                        |
| `typeSource`     | `null`          | The `DataSource` whose catalog reads the rows; see [Types and converters](#types-and-converters)     |

What comes:

* **Committed transactions, in commit order.** A rolled-back transaction never comes. Neither does one that changed
  nothing the publications cover.
* **A transaction comes after its commit, whole.** Until then PostgreSQL keeps its changes on the server, however long
  it runs.
* **Its changes carry rows of their table**, read as the rows of a query are.

## Slots

A stream reads a slot that is there, or one it creates first, as `newSlot` asks:

```sql
SELECT pg_create_logical_replication_slot('senate_archive', 'pgoutput');
```

```kotlin
startPgOutput(url, properties, "senate_archive", listOf("senatus"), newSlot = NewSlot())
```

A `newSlot` whose name a slot already has is refused with the server's `StatementException`, and that slot is left as
it was. A slot is dropped only in SQL, with `pg_drop_replication_slot`. A `NewSlot` can also copy what the tables held
before the slot was made; see [Starting from a copy of the table](#starting-from-a-copy-of-the-table).

**A persistent slot outlives every connection.** A reader that comes back to it gets every transaction it had not
acknowledged: after a crash, after a deployment, after a week away. That is what a slot is for, and also its danger:

> [!WARNING]
> A slot keeps the WAL of everything not yet acknowledged, whether anything reads from it or not. A slot nobody
> streams from any more keeps it until the disk is full. Drop the slots you stop using, or cap what one may keep with
> `max_slot_wal_keep_size`.

**A temporary slot goes when its stream closes**, and no other stream can read from it. `NewSlot(temporary = true)`
is the way to make one, since PostgreSQL ties a temporary slot to the connection that made it. It suits a reader that
rebuilds what it holds on every start, such as an in-memory copy of a table.

## Reading a transaction

A `ReplicatedTransaction` carries its `xid`, its `commitLsn` and `committedAt`, all three known before any change is
read. What it holds is read from the connection as you go through it, so a transaction of a million rows takes no more
memory than the row being read. That makes three rules:

* **`changes`, `messages` and `entries` are three views of one pass.** Read one of them, once; a second raises
  `InvalidOperationException(RESOURCE_CLOSED)`. `changes` holds the changes alone, `messages` the
  [messages](#logical-messages) alone, and `entries` both in the order they were made, each a `TransactionEntry`:
  a `Change` or a `LogicalMessage`.
* **Only inside the block.** Once the block returns, the stream reads on, and reading the transaction raises
  `RESOURCE_CLOSED`. Keep what you need from it, the rows or what you map them onto; those stay readable.
* **What the block does not read is skipped.** A block that looks only at `committedAt`, or stops halfway through the
  changes, still has the whole transaction acknowledged when it returns.

## Changes

| Change            | What it carries                                                                                                    |
|:------------------|:-------------------------------------------------------------------------------------------------------------------|
| `Change.Insert`   | `relation`, and the `new` row                                                                                      |
| `Change.Update`   | `relation`, the `old` row or `null`, the `new` row, and the [`unchanged`](#columns-an-update-did-not-send) columns |
| `Change.Delete`   | `relation`, and the `old` row                                                                                      |
| `Change.Truncate` | The truncated `relations`, `cascade` and `restartIdentity`                                                         |

`Change` is sealed, so a `when` over it needs no `else`. The first three are a `Change.OfRow`, whose `relation` is a
`PgRelation`: the table's `schema` and `name`, its `oid`, its `replicaIdentity` and the `keyColumns` that identity
names. A stream of several tables tells them apart by it:

```kotlin
for (change in transaction.changes) {
    if (change !is Change.OfRow) continue
    when (change.relation.name) {
        "senator" -> senators.apply(change)
        "legion" -> legions.apply(change)
    }
}
```

### What the old row holds

The `old` row of an update or a delete holds what the table's `REPLICA IDENTITY` names:

| `REPLICA IDENTITY`                         | `Update.old`                                                          | `Delete.old`    |
|:-------------------------------------------|:----------------------------------------------------------------------|:----------------|
| `DEFAULT` (the primary key), `USING INDEX` | The key columns, only where the update changed them; `null` otherwise | The key columns |
| `FULL`                                     | The whole row                                                         | The whole row   |

A table with no identity, `NOTHING` or `DEFAULT` without a primary key, has neither: PostgreSQL refuses its `UPDATE`
and `DELETE` while a publication publishes them.

Under `DEFAULT` the old row of a delete holds the key and nothing else, so read the key by name:
`change.old.get<Int>("id")`. An update that changed the key carries the old key in `old` and the new one in `new`; one
that did not has `old` as `null`, and the key in `new`.

## Columns an update did not send

An update does not send a TOASTed value it left as it was: one PostgreSQL stored out of line because it was still large
after compression. Whether a value is TOASTed depends on the value, not on the column, so the same column comes in one
update and not in the next. The new row of an update holds the columns that came, and `Change.Update.unchanged` names
the others. Reading one of those by name raises `MappingException(COLUMN_NOT_FOUND)`.

> [!WARNING]
> **A nullable property for an unchanged column maps as `null`.** The new row maps as the row of a query that left the
> column out, so `change.new.toObject<Senator>()` gives a `toga: String?` the value `null` where the update did not
> send the toga: the same `null` as a toga that is `NULL`. Tests with short values never see it, since only a value
> large enough to be TOASTed goes unsent.

Two ways keep a class from meeting a column that did not come:

* **`REPLICA IDENTITY FULL`** on the table. The old row then carries every value, the driver fills in those the new
  one came without, and `unchanged` is always empty. PostgreSQL then writes the whole old row to the WAL for every
  update and delete.
* **A class without the large columns**, such as `SenatorName(id, cognomen)`, or a `Map<String, V>`, which holds the
  columns that came.

## Logical messages

A transaction can write a message into the WAL beside its changes, with `pg_logical_emit_message`:

```sql
BEGIN;
UPDATE senator SET status = 'ON_CAMPAIGN' WHERE id = 1;
SELECT pg_logical_emit_message(true, 'dispatch', '{"senator": 1, "status": "ON_CAMPAIGN"}');
COMMIT;
```

With `messages = true`, the stream hands them out in their transaction, each a `LogicalMessage` with its `prefix` and
its `content` as bytes:

```kotlin
startPgOutput(url, properties, "dispatch", listOf("senatus"), messages = true).use { stream ->
    stream.forEachTransaction { transaction ->
        for (message in transaction.messages) {
            if (message.prefix == "dispatch") courier.send(message.content.decodeToString())
        }
    }
}
```

A message comes when its transaction does: after the commit, never after a rollback, in commit order. A transaction
that wrote one comes for it even where it changed nothing the publications cover, which makes a message an outbox
that needs no table: the event commits with the change it describes, or not at all.

Only transactional messages come. One written with `false` as the first argument goes into the WAL at once, whether
its transaction commits or not, and the stream does not hand it out.

`entries` gives the changes and the messages together, in the order the transaction made and wrote them:

```kotlin
for (entry in transaction.entries) when (entry) {
    is Change -> mirror.apply(entry)
    is LogicalMessage -> courier.send(entry.content.decodeToString())
}
```

## Types and converters

A stream reads its rows through the type catalog of its database, the one sessions on that database share, with every
enum, composite and converter registered on it. A table copied with a query and the stream of its changes therefore
map the same way.

Without a `typeSource`, that is the catalog of the stream's URL, keyed by host, port and database as
[every catalog is](type-system.md#scope-a-session-handle-over-global-state), and read through a session opened on the
URL where this JVM has not read it yet. Where the application's sessions reach the database under another address,
what they registered is on another catalog. Pass their `DataSource` as `typeSource`, and the stream reads with its
catalog and borrows its sessions to read it, and to run a new slot's [copy](#starting-from-a-copy-of-the-table):

```kotlin
val stream = startPgOutput(url, properties, "curia_mirror", listOf("curia_mirror"), typeSource = dataSource)
```

A `typeSource` that reaches another database than the URL is refused with `INVALID_ARGUMENT` before a slot is made or
the stream starts: the catalog maps types by OID, which no other database shares.

**A type created after the catalog was read**, such as a new enum or composite, is not in it until
[`reloadTypes()`](type-system.md#keeping-the-catalog-fresh--reloadtypes) reads it, as for a query. The first change
to a table using it ends the stream with `TypeException(TYPE_NOT_FOUND)`, and the next stream from the slot, started
once the catalog is read again, gets that transaction again.

**A type dropped after a change that uses it was written** is gone from the catalog once it is read again, and no
reload brings it back: the stream ends at that change every time, and no stream from the slot gets past it. Drop a type
only once every stream from the slot has read the changes that use it.

**A composite comes in the shape its row was written in**, not in the one its type has now, while the catalog holds
one shape: the type as it was last read. A binary composite carries the types of its attributes, not their names, so
the stream maps every value onto the names the catalog holds, by position. After an `ALTER TYPE` on a composite a
published table uses, a row of the other shape reads like this:

| Change of the type         | A row written before, read after `reloadTypes()`                                                       | A row written after, read before it                                 |
|:---------------------------|:-------------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------|
| `RENAME ATTRIBUTE`         | Reads under the new name                                                                               | Reads under the old name                                            |
| `ADD ATTRIBUTE`            | Lacks the attribute, so a class with a property for it fails with `MappingException(COLUMN_NOT_FOUND)` | Carries a value with no name, which is not read                     |
| `DROP ATTRIBUTE`, the last | Carries a value with no name, which is not read                                                        | Lacks the attribute, so a class with a property for it fails        |
| `DROP ATTRIBUTE`, another  | Reads the values after it under the wrong names                                                        | Reads the values after it under the wrong names, and lacks the last |

Wrong names come silently where the neighbouring attributes have one type, and with a `MappingException` where they do
not. A rename needs nothing more. Any other change, made while the stream runs, takes one of two ways:

* **A pause in writes.** Stop writing to the type's tables, let the stream read everything written, change the type
  and call `reloadTypes()`, then write again. The stream then never meets a row of the other shape.
* **A new type.** Move the column to one, `ALTER TABLE census ALTER COLUMN tributum TYPE tributum_v2 USING …`, and each
  shape has a type of its own: a row written before comes under the old type, by its names, and one written after
  under the new one, which ends the stream with `TYPE_NOT_FOUND` until `reloadTypes()` reads it, as any new type does.
  Writes need no pause. Drop the old type only once the stream has read past the change.

**A stream can have converters of its own.** `registerResultConverter` on the stream puts one ahead of the catalog's,
as a query's own converters are, and no query on the database sees it. A `forEach…` reads with the converters
registered when it starts:

```kotlin
startPgOutput(url, properties, "senate_archive", listOf("senatus"))
    .registerResultConverter(CognomenConverter)
    .use { stream -> archive.follow(stream) }
```

A new slot's [copy](#starting-from-a-copy-of-the-table) is a query like any other, and does not see them either: a
converter both have to read with goes on the copy's query as well, or on the catalog.

## Acknowledging

`forEachTransaction` acknowledges a transaction once its block returns. The slot then lets that transaction's WAL go,
and no later stream from it sends the transaction again. A transaction not acknowledged, because its block threw, the
stream was closed while it ran or the process died, comes again in the next stream from the slot.

**A block that throws closes the stream.** Its transaction is not acknowledged, and `forEachTransaction` throws what
the block threw once the stream is closed. Trying again takes a new stream.

**Delivery is at least once.** The server hears what was acknowledged with the next status, every `statusInterval`, and
when the stream closes. A process that dies in between gets the transactions it finished since the last status once
more. A consumer that cannot apply a transaction twice stores the `commitLsn` of the last one it applied together with
what it applied, and skips any transaction whose `commitLsn` is not past it, since commit positions grow in commit
order. A `PgLsn` stores as its `value`, or as the `X/Y` text that `toString()` writes and `PgLsn.parse` reads.

`lastReceivedLsn` and `lastAcknowledgedLsn` on the stream say how far it has got.

## Processing asynchronously

`forEachTransactionAsync` is for a block that hands a transaction to something that finishes later, such as a broker or
a batch writer, without waiting for it:

```kotlin
stream.forEachTransactionAsync(maxInFlight = 16) { transaction ->
    val elected = transaction.changes.filterIsInstance<Change.Insert>().map { it.new.toObject<Senator>() }.toList()
    archive.store(elected)   // A CompletionStage, complete once the archive has them
}
```

* **The block returns a `CompletionStage`** that completes once the transaction is processed. A coroutine makes one
  with `scope.future { … }`.
* **The block reads the transaction before it returns.** The stream reads on as soon as it has.
* **Acknowledgements keep commit order.** A transaction is acknowledged once its stage and the stages of every
  transaction before it have completed, whatever order they complete in.
* **At most `maxInFlight` transactions wait for their stages**, 16 by default. The next one waits for room, and the
  stream reads nothing meanwhile.
* **A stage that fails closes the stream**, and `forEachTransactionAsync` throws what it failed with. Its transaction
  and every one after it come again.

When the stream ends, the transactions whose stages have not completed are not acknowledged.

## Closing, and staying connected

`close()`, from any thread, tells the server what was acknowledged, ends the stream, closes its connection and makes
`forEach…` return. A transaction the stream was closed inside is not acknowledged and comes again next time: its
block's reading raises `RESOURCE_CLOSED`, which `forEachTransaction` lets go instead of throwing.

```kotlin
val stream = startPgOutput(url, properties, "curia_mirror", listOf("curia_mirror"))
val reader = Thread.ofVirtual().start { mirror.follow(stream) }

// At shutdown
stream.close()
reader.join()
```

One `forEach…` reads a stream at a time; a second raises `CONNECTION_BUSY`. Where the server ends the session,
`forEach…` raises `SessionTerminatedException`, and where the connection fails, `NetworkException`.

**The stream keeps itself connected.** The server ends a replication connection that has told it nothing for
`wal_sender_timeout`, a minute by default. The stream tells it what was received and acknowledged every
`statusInterval` from a thread of its own, so a block that spends ten minutes on one transaction does not lose the
stream. Keep `statusInterval` well under the server's timeout.

**A quiet publication does not hold the slot back.** While every transaction handed out is acknowledged, the stream
also acknowledges the positions the server reports between transactions. Writes to tables outside the publications
then move the slot on, instead of piling up WAL it would keep for a transaction that never comes.

## Starting from a copy of the table

A slot sends what was committed after it was made. A reader that holds the whole table, such as a cache, a search index
or a copy in another database, needs what was there before as well, and as of exactly that moment: a row inserted
between the copy and the slot would be missed, and one changed in between applied twice.

A `NewSlot` with a `copy` block takes that moment from the slot. Once the slot is made, and before the stream starts,
the block runs in a read-only `REPEATABLE READ` transaction that reads the database as of the slot's start, and the
stream carries on from exactly there. The mirror below keeps every column, so its table is
`ALTER TABLE senator REPLICA IDENTITY FULL` and each update's new row comes [whole](#columns-an-update-did-not-send):

```kotlin
class SenatorMirror {
    val senators = ConcurrentHashMap<Int, Senator>()

    /** Copies the table as it was when the slot was made. */
    fun copy(session: OctaviusSessionOperations) =
        session.createNativeQuery("SELECT * FROM senator").fetchObjects<Senator>().forEach { senators[it.id] = it }

    /** Applies each transaction until the stream is closed. */
    fun follow(stream: ChangeStream) = stream.forEachTransaction { transaction ->
        for (change in transaction.changes) when (change) {
            is Change.Insert -> senators[change.new["id"]] = change.new.toObject()
            is Change.Update -> {
                val id: Int = change.new["id"]
                senators[id] = change.new.toObject()
                // An update of the key leaves the senator under the old one as well.
                val oldId = change.old?.get<Int>("id")
                if (oldId != null && oldId != id) senators.remove(oldId)
            }
            is Change.Delete -> senators.remove(change.old.get<Int>("id"))
            is Change.Truncate -> senators.clear()
        }
    }
}

val mirror = SenatorMirror()
startPgOutput(
    url, properties,
    slot = "curia_mirror",
    publications = listOf("curia_mirror"),
    newSlot = NewSlot(temporary = true, copy = { mirror.copy(this) }),
    typeSource = dataSource
).use { mirror.follow(it) }
```

**The copy reads through the stream's sessions.** It runs in a session of `typeSource`, or without one, in a session
opened on the URL, so the copy and the stream map the table through the same catalog. Without a `typeSource` it reads
as the replication role, which then needs `SELECT` on the tables it copies.

**A copy that throws leaves no slot behind.** `startPgOutput` throws what the block threw, once a persistent slot is
dropped again; a temporary one goes with the connection.

The slot here is temporary because the mirror lives in memory: every start copies the table again, and nothing is left
on the server in between. A copy into something lasting takes a persistent slot and copies only on its first start, and
the store has to know whether that copy finished, since a process that dies during it leaves the slot behind:

```kotlin
startPgOutput(
    url, properties, "senate_archive", listOf("senatus"),
    newSlot = if (archive.isCopied()) null else NewSlot(copy = { archive.copyAll(this) })
).use { archive.follow(it) }
```

Where the store has no finished copy and the slot is there, a copy died with its process: drop the slot in SQL and
start again.

## Other output plugins

`startLogical` streams a slot of any output plugin, and hands out what the plugin writes as it wrote it. Its slot is
made in SQL; creating one with the stream, temporary or with a copy, is for `pgoutput` alone. Here the slot was made
with `test_decoding`, which writes each change as a line of text:

```sql
SELECT pg_create_logical_replication_slot('curia_audit', 'test_decoding');
```

```kotlin
startLogical(url, properties, "curia_audit", options = mapOf("include-xids" to "0")).use { stream ->
    while (true) {
        val data = stream.read() ?: break
        audit.write(data.payload.decodeToString())
        stream.acknowledge(data.lsn)
    }
}
```

Its `options` go to the plugin as they are. `read()` waits for the next `ReplicationData`, with its `lsn`, `sentAt`
and `payload`; `poll(timeout)` waits at most that long. Nothing is decoded and nothing is acknowledged for you:
`acknowledge(lsn)` marks everything up to that position processed, and a transaction is settled once the position of
its commit is acknowledged. Status and `close()` are as above.

## What it does not do

* **Transactions in progress.** The driver speaks version 1 of the `pgoutput` protocol, so a transaction comes once it
  has committed. Streaming a large transaction while it runs (version 2) and decoding a prepared transaction at
  `PREPARE TRANSACTION` (version 3) are not supported.
* **Messages outside a transaction**, as [above](#logical-messages).
* **Physical replication** and base backups.
