package io.github.octaviusframework.driver.lo

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}


/**
 * What a [LargeObject.seek] offset is counted from.
 *
 * @property value The constant `lo_lseek64` takes.
 */
enum class SeekWhence(val value: Int) {
    /** From the start. */
    SET(0),

    /** From the current position. */
    CUR(1),

    /** From the end. */
    END(2)
}

/**
 * An open large object, valid until [close] or the end of its transaction.
 *
 * @property fd The descriptor the server assigned.
 */
class LargeObject internal constructor(
    private val session: OctaviusSession,
    val oid: Int,
    val fd: Int
) : AutoCloseable {

    private var closed = false

    /** Refuses a descriptor [close] gave up; a closed session or an ended transaction is reported by those. */
    private fun checkClosed() {
        if (closed) throw InvalidOperationException(
            InvalidOperationExceptionReason.RESOURCE_CLOSED,
            "Large Object is already closed"
        )
    }

    /** Reads up to [length] bytes; an empty array at the end. */
    fun read(length: Int): ByteArray {
        checkClosed()
        if (length == 0) return ByteArray(0)

        return session.createNativeQuery("SELECT loread($1, $2)")
            .fetchFieldStrict<ByteArray>(fd, length)
    }

    /** Reads up to [length] bytes into [buffer] at [offset] and returns how many, or `-1` at the end. */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int {
        val bytes = read(length)
        if (bytes.isEmpty()) {
            return -1 // EOF
        }

        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        return bytes.size
    }

    /** Writes [length] bytes of [data] from [offset]. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        checkClosed()
        if (length == 0) return

        val dataToWrite = if (offset == 0 && length == data.size) {
            data
        } else {
            data.copyOfRange(offset, offset + length)
        }

        session.createNativeQuery("SELECT lowrite($1, $2)")
            .fetchFieldStrict<Int>(fd, dataToWrite)
    }

    /** A stream reading from the current position. Closing it closes this object. */
    fun inputStream(): InputStream = LargeObjectInputStream(this)

    /** A stream writing from the current position. Closing it closes this object. */
    fun outputStream(): OutputStream = LargeObjectOutputStream(this)

    /** Moves to [position], counted from [ref], and returns the new position. */
    fun seek(position: Long, ref: SeekWhence = SeekWhence.SET): Long {
        checkClosed()
        return session.createNativeQuery("SELECT lo_lseek64($1, $2, $3)")
            .fetchFieldStrict<Long>(fd, position, ref.value)
    }

    /** The current position, from the start. */
    fun tell(): Long {
        checkClosed()
        return session.createNativeQuery("SELECT lo_tell64($1)")
            .fetchFieldStrict<Long>(fd)
    }

    /** Cuts or extends the object to [length] bytes, extending with zero bytes. */
    fun truncate(length: Long) {
        checkClosed()
        session.createNativeQuery("SELECT lo_truncate64($1, $2)")
            .fetchFieldStrict<Int>(fd, length)
    }

    /** Closes the descriptor. Calls after the first do nothing. */
    override fun close() {
        if (!closed) {
            closed = true
            try {
                session.createNativeQuery("SELECT lo_close($1)")
                    .fetchFieldStrict<Int>(fd)
            } catch (e: Exception) {
                // The descriptor stays open until the transaction ends, which is visible only from here.
                logger.debug(e) { "Failed to close large object descriptor $fd (oid $oid)" }
            }
        }
    }
}
