# COPY Protocol

*An aqueduct is not a queue of buckets. Once the channel is open the water simply runs, and nobody stands at each mile
to receive it and hand it on. `COPY` opens that kind of channel over the connection you already have: the parsing,
planning and binding that a statement pays for every single row stop happening, and the two ends just move bytes until
one of them closes the sluice.*

The PostgreSQL `COPY` command is the fastest way to move bulk data in and out of the database. Instead of parsing, planning and executing a statement per row, the server opens a dedicated sub-protocol on the existing connection and both sides simply stream bytes at each other until one of them says stop. There is no per-row round trip, no per-row parameter binding, and — on the way in — no per-row plan.

`octavius-postgresql` exposes this natively through `CopyManager`, reachable at `session.copy`. The manager is created together with the session and bound to that session's connection stream, so `session.copy` is always the same instance, and every COPY it starts runs on that one connection.

Contents:
* [Two levels of API](#two-levels-of-api)
* [COPY IN (Import)](#copy-in-import)
* [COPY OUT (Export)](#copy-out-export)
* [Data formats](#data-formats)
* [Operation lifecycle](#operation-lifecycle)
* [Cancelling](#cancelling)
* [Transactions](#transactions)
* [Error handling](#error-handling)
* [Performance notes](#performance-notes)
* [Practical rules and gotchas](#practical-rules-and-gotchas)

Starting a copy puts the connection into a dedicated mode that lasts until the transfer ends, one direction only, and that single fact drives everything else in this document:

> [!WARNING]
> **The SQL is not parameterized.** `COPY` has nothing to bind, so the statement is sent verbatim. Never build one by interpolating user input — a table or column name coming from outside your code needs [validating or quoting yourself](queries.md#quoting-a-name-that-comes-from-outside). Pass a single statement, too: the driver would silently ignore anything you chained ahead of the copy with `;`.

> [!IMPORTANT]
> **The connection is occupied for the whole operation.** Between the moment a copy starts and the moment it finishes, that session can do nothing else — no queries, no transaction control. Attempting it throws `InvalidOperationException(CONNECTION_BUSY)` instead of corrupting the connection. See [Practical rules and gotchas](#practical-rules-and-gotchas).

## Two levels of API

`CopyManager` offers each direction twice: a one-shot form that pumps a whole `java.io` stream for you, and a handle you drive yourself.

| Method                                                   | Returns           | Use it when                                                                     |
|:---------------------------------------------------------|:------------------|:--------------------------------------------------------------------------------|
| `copyIn(sql, inputStream, bufferSize): Long`             | rows written      | The data already exists as a stream — a file, a socket, a byte array.           |
| `copyIn(sql: String): CopyIn`                            | handle            | You generate the rows yourself, or want control over chunking and cancellation. |
| `copyOut(sql: String, outputStream: OutputStream): Long` | **bytes** written | You just want the export landed somewhere.                                      |
| `copyOut(sql: String): CopyOut`                          | handle            | You want to process the export as it arrives, without buffering all of it.      |

> [!NOTE]
> The return values are deliberately different, not an oversight: `COPY ... FROM STDIN` ends with the server reporting how many rows it applied, while `COPY ... TO STDOUT` reports nothing comparable, so the byte count is returned instead. If you need the number of exported rows, count line breaks yourself or ask the database separately.

## COPY IN (Import)

### From an existing stream

```kotlin
// Streaming a file straight into the census
val rowsImported = FileInputStream("senators.csv").use { file ->
    session.copy.copyIn(
        "COPY senators(id, cognomen) FROM STDIN WITH (FORMAT csv, HEADER)",
        file
    )
}
println("Successfully imported $rowsImported senators.")
```

The driver reads the input in 64 KiB chunks and sends each one as it goes until the stream is exhausted, then ends the copy and returns the row count reported by the server. The chunk size is the optional third parameter (`CopyManager.DEFAULT_BUFFER_SIZE` when omitted) — worth lowering only if you are memory-constrained, since larger buffers stop paying off well before the socket does. If anything throws along the way — an `IOException` from your stream, or an error from the server — the copy is cancelled if it is still active and the original exception is rethrown, leaving the connection usable. A row the server rejects as it reads it stops the transfer at the next chunk, and the rest of the stream is not read.

The input stream is **not** closed by the driver. It is yours; close it yourself (as `use` does above).

### Writing chunks yourself

When the rows are produced in code rather than read from somewhere, take the handle:

```kotlin
val rows = session.copy.copyIn("COPY senators(id, cognomen) FROM STDIN").use { copyIn ->
    val batch = StringBuilder()
    senators.forEachIndexed { index, senator ->
        batch.append(senator.id).append('\t').append(senator.cognomen).append('\n')
        // Flush every ~32 KiB rather than once per row
        if (batch.length >= 32 * 1024 || index == senators.lastIndex) {
            val bytes = batch.toString().toByteArray(Charsets.UTF_8)
            copyIn.writeToCopy(bytes)
            batch.setLength(0)
        }
    }
    copyIn.endCopy()
}
```

`CopyIn` implements `AutoCloseable`, and `close()` cancels the operation *only if it is still active*. That makes `use { … endCopy() }` the idiomatic shape: on the happy path `endCopy()` already deactivated the handle and `close()` does nothing; on an exception the copy is aborted and the connection resynchronized before the exception escapes. The explicit try/catch calling `cancelCopy()` works just as well, it is simply more typing.

A few mechanics worth knowing:

* **`writeToCopy(data, offset = 0, length = data.size)`** sends exactly one message and flushes it. The bytes are copied into the driver's output buffer before the call returns, so reusing the same `ByteArray` for the next chunk is safe.
* **Chunk boundaries are not row boundaries.** The server reassembles a continuous byte stream, so a row may be split across two chunks, and one chunk may carry thousands of rows. What matters is that the *whole* stream is well-formed for the chosen format — the last row still needs its trailing newline in text and CSV.
* **`endCopy()` returns the row count** parsed out of the `COPY n` tag, and blocks until the server has finished applying the data.
* **A rejected row is reported by the first call after the server rejects it.** Some checks the server makes as it reads each row — a value that won't parse, a wrong column count, `NOT NULL`, `CHECK` — and it ends the copy on the spot: the next `writeToCopy` throws instead of sending, and the handle is finished. Foreign keys are checked only when the statement ends, so those always come from `endCopy()`. A duplicate key comes when the server writes out the rows it has buffered — at `endCopy()` on a small copy, from a later `writeToCopy` on a large one.

## COPY OUT (Export)

### Into an existing stream

```kotlin
val bytesExported = FileOutputStream("senators_export.csv").use { file ->
    session.copy.copyOut(
        "COPY (SELECT id, cognomen FROM senators WHERE active) TO STDOUT WITH (FORMAT csv, HEADER)",
        file
    )
}
println("Successfully exported $bytesExported bytes.")
```

Note the parenthesized `SELECT`: `COPY` is not limited to whole tables, and any query — joins, aggregates, CTEs — can be exported this way.

The driver writes each received chunk to your `OutputStream` as it arrives, so memory use stays flat regardless of result size. It does not flush or close the stream: if you wrap it in a `BufferedOutputStream`, make sure it is closed (or flushed) afterwards, or the tail of the export stays in the buffer.

### Reading chunks yourself

```kotlin
session.copy.copyOut("COPY senators TO STDOUT WITH (FORMAT csv)").use { copyOut ->
    while (true) {
        val chunk = copyOut.readFromCopy() ?: break // null == end of data
        process(chunk)
    }
}
```

`readFromCopy()` blocks until the next chunk arrives and returns it, or returns `null` once the server has finished sending and the connection is back to normal. After that, further calls keep returning `null` rather than throwing — the loop above terminates cleanly without extra state.

In practice PostgreSQL emits one chunk per row for the text and CSV formats, so a chunk usually *is* a line. The protocol does not guarantee it, and binary mode certainly doesn't behave that way. Treat the output as a byte stream and do your own line splitting if you need rows.

## Data formats

The driver is a byte pipe. It neither builds nor parses COPY payloads — everything is decided by the `WITH (...)` options in your SQL, exactly as `psql` would use them.

| Format           | Notes                                                                                                                                                                                                    |
|:-----------------|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `text` (default) | Tab-separated, `\N` for NULL, backslash escapes. The most compact of the textual options.                                                                                                                |
| `csv`            | Standard CSV; supports `HEADER`, `DELIMITER`, `QUOTE`, `ESCAPE`, `NULL`, `FORCE_QUOTE`. What you want for interchange with spreadsheets.                                                                 |
| `binary`         | The `PGCOPY` binary layout: fastest and lossless, but you are responsible for the 19-byte signature header, per-row field encoding, and the trailer. The driver will not generate or validate any of it. |

Because the type system is not involved on this path, there is no conversion of Kotlin objects here — the bytes you write are the bytes the server parses. Mapping domain objects onto rows is your code's job (or a job for [ordinary parameterized queries](queries.md), when the volume doesn't justify COPY).

That absence is a decision rather than a gap, and what decides it is the form the data is already in rather than how much of it there is. Rows that exist as objects go in through [`UNNEST`](bulk-writes.md) — a chunk at a time when there are a great many, which keeps memory wherever you put the chunk size, and keeps `RETURNING` and `ON CONFLICT` besides. Rows that arrive as bytes, from a file or a socket or another database's export, need no objects at all and go through here as they stand. An API encoding objects into `PGCOPY` frames would have to sit between the two: it needs a collection in memory to encode from, and it only earns its keep at a volume you would not be holding in memory.

If you do write the binary layout by hand, the part that catches people is that each field has to be encoded as the *destination column's* type. Nothing negotiates it: `CopyInResponse` reports how many columns there are and in which format, never their OIDs, so a four-byte integer written into a `bigint` column is discovered by the server rejecting the data rather than by anything on the way there.

Also worth remembering: `STDIN`/`STDOUT` is the client-side form and needs no special privileges beyond the table permissions. `COPY … FROM '/path/on/server'` is a *server-side* copy requiring superuser or `pg_read_server_files`, and it never enters copy mode — handing that statement to `copyIn` raises an `InvalidOperationException` (see below).

## Operation lifecycle

Both handles expose `isActive`, and both go through the same one-way transition:

| State                        | `isActive` | `CopyIn`                         | `CopyOut`                              |
|:-----------------------------|:-----------|:---------------------------------|:---------------------------------------|
| Just returned by the manager | `true`     | Ready for `writeToCopy`          | Ready for `readFromCopy`               |
| Finished normally            | `false`    | after `endCopy()`                | after `readFromCopy()` returned `null` |
| Aborted                      | `false`    | after `cancelCopy()` / `close()` | after `cancelCopy()` / `close()`       |

Once inactive, a handle is spent — there is no restarting it, and you start a new copy through the manager instead. Calling `writeToCopy` or `endCopy` on an inactive `CopyIn` throws `InvalidOperationException(RESOURCE_CLOSED)`; `readFromCopy` on an inactive `CopyOut` simply returns `null`; `cancelCopy()` on an already-inactive handle is a no-op, which is what makes `close()` safe to call unconditionally.

## Cancelling

The two directions cancel differently, and the difference is not cosmetic.

**`CopyIn.cancelCopy()` genuinely aborts.** It tells the server to fail the copy, so everything received in it is discarded and an error comes back — which the driver *deliberately swallows*, because cancelling is not a failure from the caller's point of view. Nothing lands in the table, and no exception is raised.

**`CopyOut.cancelCopy()` only stops your reading.** There is no way for a client to tell the server to stop sending, so the method marks the handle inactive and then reads and discards the rest of the export. It leaves the connection clean and reusable — which is the point — but it will block for as long as the server needs to finish producing the result. Cancelling an export of a hundred million rows on the first chunk still waits for all hundred million.

If you only need part of an export, put the limit in the SQL (`COPY (SELECT … LIMIT 1000) TO STDOUT`) rather than relying on cancellation.

## Transactions

COPY runs on the session's own connection, so it obeys the usual transaction rules with no special handling:

* In auto-commit mode, the copy is its own transaction — it commits when `endCopy()` succeeds.
* Inside `session.transaction.required { … }`, it participates in that transaction and is rolled back with everything else if the block throws.

That second form is the usual choice for a reload, where the truncate and the import must stand or fall together:

```kotlin
session.transaction.required {
    createNativeQuery("TRUNCATE TABLE senators").execute()

    FileInputStream("census.csv").use { file ->
        session.copy.copyIn("COPY senators FROM STDIN WITH (FORMAT csv, HEADER)", file)
    }
}
```

A single COPY IN is all-or-nothing by itself as well: a malformed line or a constraint violation aborts the entire copy, and no rows from it remain. There is no "skip the bad rows" mode — the usual workaround is to import into an unconstrained staging table and clean the data with SQL from there.

> [!TIP]
> Creating or truncating the target table inside the same transaction as the copy is what unlocks PostgreSQL's bulk-load shortcuts: `WITH (FREEZE)` is only accepted in that situation, and it marks the loaded rows frozen so no later hint-bit rewrite or anti-wraparound vacuum has to touch them. (Skipping WAL for the load itself additionally requires `wal_level = minimal`, which most production setups don't run.) Outside that pattern neither shortcut is available.

## Error handling

Errors from the server follow the driver's normal path: the exchange is drained to its end, the SQLSTATE is routed through `ExceptionTranslator`, and a typed exception is thrown with the connection left in a usable state. The categories you should expect on this path:

| Situation                                                          | What is thrown                                                                                                                                                            |
|:-------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Malformed input line, wrong column count, unparsable value         | `DataException` — SQLSTATE class `22` (`22P04` *bad_copy_file_format*, `22P02` for a value that won't parse). `dbMessage`, `details` and `where` name the offending line. |
| Duplicate key, FK or NOT NULL violation among the rows             | `ConstraintViolationException` with the usual reason enum.                                                                                                                |
| No permission on the table, or a server-side `COPY … FROM '/file'` | `PermissionDeniedException` (`42501`), raised before copy mode is ever entered.                                                                                           |
| Statement ran but never entered copy mode                          | `InvalidOperationException(UNEXPECTED_RESULT)` — *"Query did not initiate a COPY IN/OUT operation."*                                                                      |
| Handle used after it finished                                      | `InvalidOperationException(RESOURCE_CLOSED)` — handles are single-use; start a new one through the manager.                                                               |
| Session used for anything else while a copy is open                | `InvalidOperationException(CONNECTION_BUSY)` — raised before anything goes out, so the transfer itself is untouched.                                                      |
| Socket failure mid-transfer                                        | `NetworkException`. Unlike the others, this one leaves the connection broken for good.                                                                                    |
| The server ended the session mid-transfer                          | `SessionTerminatedException` — `transaction_timeout` ran out, or `pg_terminate_backend()`. Broken for good as above, and `cancelCopy()` does nothing on it.               |

The `UNEXPECTED_RESULT` case deserves a note, because its wording is generic while the cause usually isn't: it means the SQL you passed *was* accepted by the server but produced something other than a copy — most often a plain `SELECT` reaching `copyOut`, or a server-side file copy reaching `copyIn`. Full details of the hierarchy live in [Error Handling and Exceptions](exceptions.md).

## Performance notes

COPY exists to beat row-by-row insertion, and it does. It is not part of the benchmark suite yet — the write figures in [Performance](performance.md) cover single, `UNNEST` and batched inserts, which is the ceiling COPY is meant to go above rather than a measurement of it. Two things determine whether you actually get that margin:

* **Chunk size on the write path.** Every `writeToCopy` call is one protocol message *and* one flush, which means a syscall — two, counting the look at the socket for anything the server has said. Calling it once per row turns a bulk load back into a per-row round trip of a different kind. Accumulate into a buffer of tens of kilobytes and write that — the stream-based `copyIn` overload uses 64 KiB, which is a reasonable target to match.
* **Indexes and triggers.** They are still evaluated per row, and on a large load they dominate everything the protocol does. Dropping and recreating indexes around the copy is standard practice for a full reload.

For exports, the streaming handle keeps memory constant; the one-shot overload does too, since it writes each chunk out as it arrives rather than accumulating.

## Practical rules and gotchas

* **One copy at a time, and nothing else on that session.** While a transfer is open, any other use of the session — a query, a second `COPY`, starting a notification listener — is refused with `InvalidOperationException(CONNECTION_BUSY)` rather than being interleaved into the transfer. The check is on connection state, not on the lock, so it catches the single-threaded case too: calling `createNativeQuery(...)` inside your own `writeToCopy` loop fails immediately instead of desynchronizing the connection.
* **A copy you never finish costs you the connection.** Closing a session with a transfer still open evicts that connection instead of returning it, so a pooled connection is never handed to the next borrower mid-transfer. Ending the transfer for you is not on offer: `CopyOut.cancelCopy()` has to read the rest of the export first (see [Cancelling](#cancelling)), and `close()` runs on whoever gave the session back — a `finally`, or a pool reclaiming its connection. Nothing of the copy is lost that a cancel would have kept, a COPY IN that never reached `endCopy()` committing nothing either way; what it costs is the connection, so reach a terminal state yourself — `use` gets this right for free.
* **Close your own streams.** The driver never closes an `InputStream` or `OutputStream` you passed in, and never flushes the latter.
* **`copyOut` returns bytes, `copyIn` returns rows.** Easy to conflate when both are `Long`.
* **The row count comes from the server's tag**, so it reflects what was actually applied — including rows dropped by a `BEFORE INSERT` trigger returning `NULL`.
