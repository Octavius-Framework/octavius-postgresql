package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import kotlin.time.Instant

/**
 * A committed transaction of a [ChangeStream]: its [xid], where and when it committed, and its [changes].
 *
 * The changes are read from the connection as they are consumed, so a transaction of any size takes no more
 * memory than the change being read. They can be read once, and only in the block the transaction was handed to.
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
     * The transaction's changes, in the order it made them.
     *
     * @throws InvalidOperationException `RESOURCE_CLOSED` from the iteration if the stream ends before the
     *   transaction's commit, or if the changes are read again or after the block; `UNEXPECTED_RESULT` if the
     *   server sends what a transaction cannot hold.
     */
    val changes: Sequence<Change> = Sequence {
        if (!open || iterated) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.RESOURCE_CLOSED,
                "A transaction's changes can be read once, inside the block it was handed to."
            )
        }
        iterated = true
        Changes()
    }

    private inner class Changes : Iterator<Change> {
        private var pending: Change? = null

        override fun hasNext(): Boolean {
            if (pending != null) return true
            if (endLsn != null) return false
            if (!open) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.RESOURCE_CLOSED,
                    "A transaction's changes can only be read inside the block it was handed to."
                )
            }
            pending = advance() ?: return false
            return true
        }

        override fun next(): Change {
            if (!hasNext()) throw NoSuchElementException()
            return pending!!.also { pending = null }
        }
    }

    /** The next change, or `null` at the commit. */
    private fun advance(): Change? = when (val frame = next()) {
        null -> {
            val ended = InvalidOperationException(
                InvalidOperationExceptionReason.RESOURCE_CLOSED,
                "The stream ended before the commit of transaction $xid. It was not acknowledged, and comes again " +
                    "in the next stream from the slot."
            )
            endedEarly = ended
            throw ended
        }
        is PgOutputFrame.Of -> frame.change
        is PgOutputFrame.Commit -> {
            endLsn = frame.endLsn
            null
        }
        is PgOutputFrame.Begin -> throw InvalidOperationException(
            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
            "pgoutput began transaction ${frame.xid} inside transaction $xid."
        )
    }

    /** Reads what the block left up to the commit, and closes the changes: where the commit ends, or `null`. */
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

    /** Closes the changes, where the block failed. */
    internal fun close() {
        open = false
    }

    override fun toString(): String = "ReplicatedTransaction(xid=$xid, commitLsn=$commitLsn)"
}
