package io.github.octaviusframework.driver.io

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * A highly optimized, buffered output stream tailored for the PostgreSQL wire protocol.
 *
 * It provides specialized methods for efficiently writing protocol primitives
 * like bytes, integers, shorts, and null-terminated C-style strings.
 * Internal buffering minimizes the number of underlying network write operations.
 */
internal class PgOutputStream(private var outputStream: OutputStream) {
    private val buffer = ByteArray(8192)
    private var position = 0

    /**
     * Swaps the underlying stream, flushing what is buffered first so nothing written before the swap is
     * lost. The read side discards instead; see `PgInputStream.changeStream`.
     */
    fun changeStream(newStream: OutputStream) {
        flushBuffer()
        this.outputStream = newStream
    }

    private fun ensureSpace(needed: Int) {
        if (position + needed > buffer.size) {
            flushBuffer()
        }
    }

    private fun flushBuffer() {
        if (position > 0) {
            outputStream.write(buffer, 0, position)
            position = 0
        }
    }

    /** Buffers one byte, flushing first if the buffer is full. */
    fun writeByte(b: Byte) {
        if (position >= buffer.size) {
            flushBuffer()
        }
        buffer[position++] = b
    }

    /** Buffers a 32-bit integer, most significant byte first. */
    fun writeInt(i: Int) {
        ensureSpace(4)
        buffer[position++] = (i ushr 24).toByte()
        buffer[position++] = (i ushr 16).toByte()
        buffer[position++] = (i ushr 8).toByte()
        buffer[position++] = i.toByte()
    }

    /**
     * Buffers an unsigned 16-bit integer, most significant byte first - taken as an `Int` because the
     * protocol's counts run to 65535 and a `Short` would make half of them negative.
     */
    fun writeShort(s: Int) {
        require(s in 0..65535) { "Value $s out of bounds for unsigned 16-bit short" }
        ensureSpace(2)
        buffer[position++] = (s ushr 8).toByte()
        buffer[position++] = s.toByte()
    }

    /** Buffers [length] bytes of [bytes] starting at [offset], flushing as often as it needs to. */
    fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val space = buffer.size - position
            if (space == 0) {
                flushBuffer()
                continue
            }
            val toCopy = minOf(space, remaining)
            System.arraycopy(bytes, currentOffset, buffer, position, toCopy)
            position += toCopy
            currentOffset += toCopy
            remaining -= toCopy
        }
    }

    /**
     * Writes a null-terminated string.
     */
    fun writeCString(s: String) = writeCString(s.toByteArray(StandardCharsets.UTF_8))

    /** Writes a null-terminated string already encoded, for a message that needed its bytes to count its length. */
    fun writeCString(bytes: ByteArray) {
        writeBytes(bytes)
        writeByte(0)
    }

    /** Empties the buffer into the underlying stream and flushes that too. */
    fun flush() {
        flushBuffer()
        outputStream.flush()
    }
}
