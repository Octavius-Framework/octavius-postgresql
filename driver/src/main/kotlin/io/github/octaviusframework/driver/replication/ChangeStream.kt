package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * The committed transactions of a logical slot made with `pgoutput`, in commit order, each handed to a block that
 * processes it: [forEachTransaction] acknowledges a transaction once its block returns, [forEachTransactionAsync]
 * once the stage its block returns completes. A transaction not acknowledged comes again in the next stream from
 * the slot. [close] ends the stream and closes its connection, from any thread.
 *
 * With every transaction handed out acknowledged, the stream also acknowledges the positions the server reports
 * without data, so writes to tables outside the publications move the slot on instead of holding its WAL back.
 */
class ChangeStream internal constructor(
    private val stream: LogicalReplicationStream,
    private val decoder: PgOutputDecoder,
    private val statusInterval: Duration
) : AutoCloseable {

    /** Whether the stream runs. Once it has ended, by [close] or by the server, its connection is closed. */
    val isActive: Boolean
        get() = stream.isActive

    /** The furthest position the server has reported, with data or without. */
    val lastReceivedLsn: PgLsn
        get() = stream.lastReceivedLsn

    /** The furthest position acknowledged, up to which the slot may let the WAL go. */
    val lastAcknowledgedLsn: PgLsn
        get() = stream.lastAcknowledgedLsn

    /** Whether a `forEach` is running; one at a time reads the stream. */
    private val reading = AtomicBoolean(false)

    /** Guards what follows, which completions on other threads change as well as the reading thread. */
    private val lock = ReentrantLock()
    private val settled = lock.newCondition()

    /** Whether a transaction has been begun and not yet handed back. */
    private var inTransaction = false

    /** The transactions handed back but not acknowledged, in commit order. */
    private val inFlight = ArrayDeque<InFlight>()

    /** What the first failed stage failed with. */
    private var failure: Throwable? = null

    /** The stream's own converters, in registration order; `null` until one is registered. */
    private var localResultConverters: MutableList<ResultConverter<*, *>>? = null

    /** A transaction whose stage has yet to settle, and where its commit ends. */
    private class InFlight(val endLsn: PgLsn) {
        var completed = false
    }

    init {
        // Nothing is handed out yet.
        stream.followKeepalives = true
    }

    /**
     * Hands each transaction to [block], and acknowledges it once [block] returns. Returns once the stream has
     * ended, by [close] or by the server; a transaction it ended inside is not acknowledged, and its block's read
     * of the changes raises `RESOURCE_CLOSED`, which this lets go.
     *
     * @throws InvalidOperationException `CONNECTION_BUSY` if another `forEach` is reading the stream.
     * @throws TypeException if a column's type is not in the catalog, or its value came as text.
     * @throws SessionTerminatedException if the server ended the session.
     * @throws NetworkException if the connection failed.
     * @throws Throwable what [block] threw, once the stream is closed; its transaction is not acknowledged.
     */
    fun forEachTransaction(block: (ReplicatedTransaction) -> Unit) = consume { transaction ->
        block(transaction)
        val endLsn = transaction.finish() ?: return@consume false
        stream.acknowledge(endLsn)
        lock.withLock {
            inTransaction = false
            follow()
        }
        true
    }

    /**
     * Hands each transaction to [block], whose returned stage stands for its processing, and acknowledges it once
     * that stage and the stages of every transaction before it have completed. [block] reads the changes before
     * it returns, since the stream reads on. At most [maxInFlight] transactions wait for their stages; the next
     * one waits for room.
     *
     * Returns once the stream has ended, by [close] or by the server; transactions whose stages had not completed
     * by then are not acknowledged. A stage that fails closes the stream, and what it failed with is thrown.
     *
     * @throws InvalidOperationException `CONNECTION_BUSY` if another `forEach` is reading the stream,
     *   `INVALID_ARGUMENT` if [maxInFlight] is not positive.
     * @throws TypeException if a column's type is not in the catalog, or its value came as text.
     * @throws SessionTerminatedException if the server ended the session.
     * @throws NetworkException if the connection failed.
     * @throws Throwable what [block] threw, or the first failed stage failed with, once the stream is closed.
     */
    fun forEachTransactionAsync(maxInFlight: Int = 16, block: (ReplicatedTransaction) -> CompletionStage<*>) {
        if (maxInFlight < 1) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "maxInFlight must be positive, was $maxInFlight."
            )
        }
        consume { transaction ->
            val stage = block(transaction)
            val endLsn = transaction.finish() ?: return@consume false
            val entry = InFlight(endLsn)
            lock.withLock {
                inTransaction = false
                inFlight.addLast(entry)
            }
            stage.whenComplete { _, error -> settle(entry, error) }
            awaitRoom(maxInFlight)
        }
        lock.withLock { failure }?.let { throw it }
    }

    /**
     * Registers [converter] for this stream alone, ahead of the catalog's and of those registered before it, as a
     * query's own converter is. A `forEach` reads with the converters registered when it starts.
     */
    fun registerResultConverter(converter: ResultConverter<*, *>): ChangeStream {
        lock.withLock {
            (localResultConverters ?: mutableListOf<ResultConverter<*, *>>().also { localResultConverters = it })
                .add(converter)
        }
        return this
    }

    /** Tells the server what was acknowledged, ends the stream and closes its connection. Safe from any thread. */
    override fun close() = stream.close()

    /**
     * Hands transactions to [handle] until the stream ends or [handle] says to stop. A failure of [handle] closes
     * the stream, unless it is the stream's end met inside a transaction.
     */
    private inline fun consume(handle: (ReplicatedTransaction) -> Boolean) {
        if (!reading.compareAndSet(false, true)) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.CONNECTION_BUSY,
                "Another forEach is reading this stream."
            )
        }
        // Copied, since the rows outlive the reading, and a registration meanwhile is for the next one.
        decoder.types.localConverters = lock.withLock { localResultConverters?.toList() }
        try {
            while (true) {
                val transaction = nextTransaction() ?: return
                val handed = try {
                    handle(transaction)
                } catch (e: Throwable) {
                    transaction.close()
                    val ended = transaction.endedEarly
                    if (ended != null && generateSequence(e) { it.cause }.any { it === ended }) return
                    closeAfter(e)
                    throw e
                }
                if (!handed) return
            }
        } finally {
            reading.set(false)
        }
    }

    /** Reads up to the next transaction's start, or `null` once the stream has ended. */
    private fun nextTransaction(): ReplicatedTransaction? {
        val frame = nextFrame() ?: return null
        if (frame !is PgOutputFrame.Begin) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                "pgoutput sent a change or a commit outside a transaction."
            )
        }
        lock.withLock {
            inTransaction = true
            follow()
        }
        return ReplicatedTransaction(frame.xid, frame.commitLsn, frame.committedAt, ::nextFrame)
    }

    /** The next frame of the stream, past what the decoder keeps to itself, or `null` once it has ended. */
    private fun nextFrame(): PgOutputFrame? {
        while (true) {
            val data = stream.read() ?: return null
            decoder.decode(data)?.let { return it }
        }
    }

    /**
     * Settles [entry]'s stage: acknowledges every transaction from the oldest whose stage has completed, or on
     * [error] records it and closes the stream.
     */
    private fun settle(entry: InFlight, error: Throwable?) {
        lock.withLock {
            if (error == null) {
                entry.completed = true
                while (inFlight.firstOrNull()?.completed == true) stream.acknowledge(inFlight.removeFirst().endLsn)
                follow()
            } else if (failure == null) {
                failure = if (error is CompletionException && error.cause != null) error.cause else error
            }
            settled.signalAll()
        }
        if (error != null) {
            try {
                stream.close()
            } catch (e: Exception) {
                logger.debug(e) { "Replication stream did not close cleanly after a failed stage" }
            }
        }
    }

    /**
     * Waits while [maxInFlight] transactions wait for their stages, or until the stream ends. Whether to read on:
     * no stage has failed.
     */
    private fun awaitRoom(maxInFlight: Int): Boolean = lock.withLock {
        while (inFlight.size >= maxInFlight && failure == null && stream.isActive) {
            settled.await(statusInterval.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        }
        failure == null
    }

    /** Closes the stream after [failure] of a block, which the close's own failure is added to. */
    private fun closeAfter(failure: Throwable) {
        try {
            stream.close()
        } catch (e: Exception) {
            failure.addSuppressed(e)
        }
    }

    /** Keepalives are acknowledged where nothing handed out waits for it. Under [lock]. */
    private fun follow() {
        stream.followKeepalives = !inTransaction && inFlight.isEmpty()
    }
}
