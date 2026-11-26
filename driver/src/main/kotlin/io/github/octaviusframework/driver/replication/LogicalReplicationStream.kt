package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.io.getLongBE
import io.github.octaviusframework.driver.message.backend.BackendCopyDataMessage
import io.github.octaviusframework.driver.message.backend.BackendCopyDoneMessage
import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage
import io.github.octaviusframework.driver.message.backend.ReadyForQueryMessage
import io.github.octaviusframework.driver.message.frontend.FrontendCopyDataMessage
import io.github.octaviusframework.driver.message.frontend.FrontendCopyDoneMessage
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** Microseconds from the Unix epoch to the PostgreSQL one, 2000-01-01, which replication timestamps count from. */
private const val PG_EPOCH_OFFSET_MICROS = 946_684_800_000_000L

/** One message of a slot's output plugin, and the WAL position it was decoded at. */
class ReplicationData internal constructor(
    /** Where in the WAL the change this carries was decoded; acknowledging a commit's position settles its transaction. */
    val lsn: PgLsn,
    /** When the server sent it. */
    val sentAt: Instant,
    /** The output plugin's message, as the plugin wrote it. */
    val payload: ByteArray
) {
    override fun toString(): String = "ReplicationData(lsn=$lsn, ${payload.size} bytes)"
}

/**
 * The changes of a logical slot as the server sends them, in commit order, over a connection of the stream's own:
 * [read] them, [acknowledge] what has been processed, [close] to end the stream. The slot keeps the WAL of everything
 * not acknowledged, and a later stream starts after the last acknowledged position.
 *
 * The server is told what was received and acknowledged every status interval, whether a thread reads or not, so a
 * reader that takes long over a message does not have the stream ended by the server's `wal_sender_timeout`. It is
 * also told whenever it asks, while a thread is in [read] or [poll].
 */
class LogicalReplicationStream internal constructor(
    private val stream: PgStream,
    statusInterval: Duration
) : AutoCloseable {

    private val statusIntervalNanos = statusInterval.inWholeNanoseconds

    /** Whether the stream runs. Once it has ended, by [close] or by the server, its connection is closed. */
    @Volatile
    var isActive: Boolean = true
        private set

    private val received = AtomicLong(0)
    private val acknowledged = AtomicLong(0)

    /** The furthest position the server has reported, with data or without. */
    val lastReceivedLsn: PgLsn
        get() = PgLsn(received.get())

    /** The furthest position acknowledged, up to which the slot may let the WAL go. */
    val lastAcknowledgedLsn: PgLsn
        get() = PgLsn(acknowledged.get())

    /**
     * Held for every write, so [close] can write while another thread waits in [read] holding the connection's own
     * lock.
     */
    private val writeLock = ReentrantLock()

    /** Whether this side has ended copy mode; nothing but the end of the exchange may follow. Under [writeLock]. */
    private var copyDoneSent = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var nextStatusAt = System.nanoTime() + statusIntervalNanos

    /** Sends the statuses that fall due while no thread reads, until the stream ends. */
    private val heartbeat: Thread = Thread.ofVirtual().name("octavius-replication-status").unstarted { beat() }

    init {
        heartbeat.start()
    }

    /**
     * The next message, waiting for it as long as it takes, or `null` once the stream has ended.
     *
     * @throws SessionTerminatedException if the server ended the session.
     * @throws NetworkException if the connection failed.
     */
    fun read(): ReplicationData? = receive(deadline = null)

    /**
     * The next message, or `null` if none arrives within [timeout] or the stream has ended; [isActive] says which.
     *
     * @throws SessionTerminatedException if the server ended the session.
     * @throws NetworkException if the connection failed.
     */
    fun poll(timeout: Duration): ReplicationData? =
        if (timeout.isInfinite()) read() else receive(deadline = System.nanoTime() + timeout.inWholeNanoseconds)

    /**
     * Marks everything up to [lsn] as processed, which the server hears with the next status. A position behind one
     * already acknowledged changes nothing. Safe from any thread.
     */
    fun acknowledge(lsn: PgLsn) {
        acknowledged.accumulateAndGet(lsn.value) { current, new ->
            if (java.lang.Long.compareUnsigned(new, current) > 0) new else current
        }
    }

    /**
     * Tells the server what was acknowledged, ends the stream and closes its connection. Messages that arrive
     * meanwhile are dropped; not acknowledged, they come again in the next stream. Safe from another thread than the
     * one reading, whose [read] then returns `null`. Does nothing once the stream has ended.
     */
    override fun close() {
        if (!isActive) return
        try {
            // Where the server dropped the stream with the connection, there is nothing to end.
            if (stream.isBroken) return
            stopRequested = true
            writeLock.withLock {
                if (!copyDoneSent && isActive) {
                    sendStatus()
                    endCopy()
                }
            }
            stream.lock.withLock {
                if (isActive) finish(null)
            }
        } finally {
            end()
        }
    }

    /**
     * Sends each status as it falls due, until the stream ends. Woken by [end] rather than interrupted, since an
     * interrupt would close the socket under a write.
     */
    private fun beat() {
        try {
            while (isActive && !stream.isBroken) {
                val wait = nextStatusAt - System.nanoTime()
                if (wait > 0) {
                    LockSupport.parkNanos(this, wait)
                } else {
                    writeLock.withLock { if (!copyDoneSent && isActive) sendStatus() }
                }
            }
        } catch (e: Exception) {
            // The connection failed under the write; the reader meets it.
            logger.debug(e) { "[PID: ${stream.processId}] Replication status could not be sent" }
        }
    }

    /**
     * Marks the stream ended, lets [heartbeat] go and closes the connection, which no second stream can use. Under
     * [writeLock], so the close does not cut a status in half.
     */
    private fun end() {
        isActive = false
        LockSupport.unpark(heartbeat)
        writeLock.withLock { stream.close() }
    }

    private fun receive(deadline: Long?): ReplicationData? = stream.lock.withLock {
        try {
            while (isActive) {
                var now = System.nanoTime()
                if (now - nextStatusAt >= 0) {
                    writeLock.withLock { if (!copyDoneSent) sendStatus() }
                    now = System.nanoTime()
                }
                if (deadline != null && now - deadline >= 0) return null

                val untilStatus = nextStatusAt - now
                val wait = if (deadline == null) untilStatus else minOf(untilStatus, deadline - now)
                val waitMs = ((wait + 999_999) / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

                when (val msg = stream.pollMessage(waitMs) ?: continue) {
                    is BackendCopyDataMessage -> {
                        val data = msg.data
                        when (data[0].toInt().toChar()) {
                            'w' -> {
                                val start = data.getLongBE(1)
                                advanceReceived(start)
                                // Dropped once stop has been asked for; not acknowledged, it comes again.
                                if (!stopRequested) {
                                    return ReplicationData(
                                        PgLsn(start),
                                        pgTimestamp(data.getLongBE(17)),
                                        data.copyOfRange(25, data.size)
                                    )
                                }
                            }
                            'k' -> {
                                advanceReceived(data.getLongBE(1))
                                if (data[17].toInt() != 0) writeLock.withLock { if (!copyDoneSent) sendStatus() }
                            }
                            else -> logger.trace { "[PID: ${stream.processId}] Ignoring replication message '${data[0].toInt().toChar()}'" }
                        }
                    }
                    is BackendCopyDoneMessage -> {
                        writeLock.withLock { if (!copyDoneSent) endCopy() }
                        finish(null)
                    }
                    is ErrorOrNoticeMessage -> finish(msg)
                    is ReadyForQueryMessage -> end()
                    else -> { /* Ignore */ }
                }
            }
            null
        } catch (e: Exception) {
            if (stream.isBroken) end()
            throw e
        }
    }

    private fun advanceReceived(lsn: Long) {
        received.accumulateAndGet(lsn) { current, new ->
            if (java.lang.Long.compareUnsigned(new, current) > 0) new else current
        }
    }

    /** Sends a standby status update: received as written, acknowledged as flushed and applied. Under [writeLock]. */
    private fun sendStatus() {
        val ack = acknowledged.get()
        val message = ByteArray(34)
        message[0] = 'r'.code.toByte()
        message.putLongBE(1, received.get())
        message.putLongBE(9, ack)
        message.putLongBE(17, ack)
        message.putLongBE(25, System.currentTimeMillis() * 1_000 - PG_EPOCH_OFFSET_MICROS)
        message[33] = 0
        stream.writeMessage(FrontendCopyDataMessage(message))
        stream.flush()
        nextStatusAt = System.nanoTime() + statusIntervalNanos
        logger.trace { "[PID: ${stream.processId}] Replication status sent: received $lastReceivedLsn, acknowledged ${PgLsn(ack)}" }
    }

    /** Ends copy mode from this side. Under [writeLock]. */
    private fun endCopy() {
        stream.writeMessage(FrontendCopyDoneMessage())
        stream.flush()
        copyDoneSent = true
    }

    /**
     * Reads what is left of the exchange, up to its `ReadyForQuery`, and raises [error] or the error that came
     * with the rest. Under the connection's lock.
     */
    private fun finish(error: ErrorOrNoticeMessage?) {
        var failure = error
        try {
            while (true) {
                when (val msg = stream.receiveMessage()) {
                    is ErrorOrNoticeMessage -> if (failure == null) failure = msg
                    is ReadyForQueryMessage -> break
                    // Data sent before the server saw the end, its own CopyDone, CommandComplete.
                    else -> { /* Ignore */ }
                }
            }
        } finally {
            end()
        }
        logger.debug { "[PID: ${stream.processId}] Replication stopped at $lastReceivedLsn, acknowledged $lastAcknowledgedLsn" }
        if (failure != null) throw ExceptionTranslator.translate(failure)
    }
}

/** A replication timestamp: microseconds since 2000-01-01 UTC. */
private fun pgTimestamp(micros: Long): Instant {
    val unixMicros = micros + PG_EPOCH_OFFSET_MICROS
    return Instant.fromEpochSeconds(Math.floorDiv(unixMicros, 1_000_000L), Math.floorMod(unixMicros, 1_000_000L) * 1_000L)
}

private fun ByteArray.putLongBE(offset: Int, value: Long) {
    for (i in 0 until 8) {
        this[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
