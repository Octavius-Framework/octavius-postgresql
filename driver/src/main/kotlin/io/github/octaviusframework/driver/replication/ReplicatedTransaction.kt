package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import kotlin.time.Instant

/**
 * A committed transaction of a [ChangeStream]: its [xid], where and when it committed, and what it holds, the
 * changes it made and the messages it wrote.
 *
 * They are read from the connection as they are consumed, so a transaction of any size takes no more memory than
 * the one being read. [changes], [messages] and [entries] are three views of one pass over the transaction: one of
 * them can be read, once, and only in the block the transaction was handed to.
 */
class ReplicatedTransaction internal constructor(
    val xid: Long,
    /** The position of the transaction's commit. */
    val commitLsn: PgLsn,
    val committedAt: Instant,
    /** The stream's next frame, or `null` once it has ended. */
    private val next: () -> PgOutputFrame?
) {
    /** Where the commit ends, once it has been read; acknowledging it settles the transaction. */
    internal var endLsn: PgLsn? = null
        private set

    /** What reading raised where the stream ended before the commit; the block that lets it out has not failed. */
    internal var endedEarly: InvalidOperationException? = null
        private set

    private var open = true
    private var iterated = false

    /**
     * The transaction's changes, in the order it made them, without the messages it wrote; [entries] has both.
     *
     * @throws InvalidOperationException `RESOURCE_CLOSED` from the iteration if the stream ends before the
     *   transaction's commit, or if the transaction is read again or after the block; `UNEXPECTED_RESULT` if the
     *   server sends what a transaction cannot hold.
     */
    val changes: Sequence<Change> = view { (it as? PgOutputFrame.Of)?.change }

    /**
     * The messages the transaction wrote with `pg_logical_emit_message(true, …)`, in the order it wrote them,
     * without its changes; [entries] has both. Empty unless `startPgOutput` was asked for messages.
     *
     * @throws InvalidOperationException as [changes] does.
     */
    val messages: Sequence<LogicalMessage> = view { (it as? PgOutputFrame.Message)?.message }

    /**
     * The transaction's changes and messages together, in the order it made and wrote them.
     *
     * @throws InvalidOperationException as [changes] does.
     */
    val entries: Sequence<TransactionEntry> = view {
        when (it) {
            is PgOutputFrame.Of -> it.change
            is PgOutputFrame.Message -> it.message
            else -> null
        }
    }

    /** A view of the one pass over the transaction, of what [pick] takes from its frames. */
    private fun <T : Any> view(pick: (PgOutputFrame) -> T?): Sequence<T> = Sequence {
        if (!open || iterated) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.RESOURCE_CLOSED,
                "A transaction's changes, messages and entries are one pass over it: read one of them, once, " +
                    "inside the block it was handed to."
            )
        }
        iterated = true
        View(pick)
    }

    private inner class View<T : Any>(private val pick: (PgOutputFrame) -> T?) : Iterator<T> {
        private var pending: T? = null

        override fun hasNext(): Boolean {
            while (pending == null) {
                if (endLsn != null) return false
                if (!open) {
                    throw InvalidOperationException(
                        InvalidOperationExceptionReason.RESOURCE_CLOSED,
                        "A transaction can only be read inside the block it was handed to."
                    )
                }
                pending = pick(advance() ?: return false)
            }
            return true
        }

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return pending!!.also { pending = null }
        }
    }

    /** The transaction's next frame, a change or a message, or `null` at the commit. */
    private fun advance(): PgOutputFrame? = when (val frame = next()) {
        null -> {
            val ended = InvalidOperationException(
                InvalidOperationExceptionReason.RESOURCE_CLOSED,
                "The stream ended before the commit of transaction $xid. It was not acknowledged, and comes again " +
                        "in the next stream from the slot."
            )
            endedEarly = ended
            throw ended
        }

        is PgOutputFrame.Of, is PgOutputFrame.Message -> frame
        is PgOutputFrame.Commit -> {
            endLsn = frame.endLsn
            null
        }

        is PgOutputFrame.Begin -> throw InvalidOperationException(
            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
            "pgoutput began transaction ${frame.xid} inside transaction $xid."
        )
    }

    /** Reads what the block left up to the commit, and closes the transaction: where the commit ends, or `null`. */
    internal fun finish(): PgLsn? {
        try {
            while (endLsn == null) {
                if (endedEarly != null) return null
                try {
                    advance()
                } catch (e: InvalidOperationException) {
                    if (e === endedEarly) return null
                    throw e
                }
            }
            return endLsn
        } finally {
            open = false
        }
    }

    /** Closes the transaction, where the block failed. */
    internal fun close() {
        open = false
    }

    override fun toString(): String = "ReplicatedTransaction(xid=$xid, commitLsn=$commitLsn)"
}
