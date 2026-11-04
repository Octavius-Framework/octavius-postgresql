package io.github.octaviusframework.driver.io

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** A buffered reader of protocol primitives: bytes, big-endian integers, C strings. */
internal class PgInputStream(private var inputStream: InputStream) {
    private val buffer = ByteArray(8192)
    private var position = 0
    private var limit = 0
    
    // Reused for C strings.
    private var stringBuffer = ByteArray(256)

    /**
     * Swaps the underlying stream for the TLS upgrade. Plaintext still buffered is dropped, since only an intermediary
     * could have sent any after the server's `S`.
     */
    fun changeStream(newStream: InputStream) {
        this.inputStream = newStream
        this.position = 0
        this.limit = 0
    }

    private fun ensure(count: Int) {
        if (limit - position >= count) return
        
        val remaining = limit - position
        if (remaining > 0) {
            System.arraycopy(buffer, position, buffer, 0, remaining)
        }
        position = 0
        limit = remaining
        
        while (limit < count) {
            val read = inputStream.read(buffer, limit, buffer.size - limit)
            if (read == -1) throw EOFException()
            limit += read
        }
    }

    private fun fillBuffer() {
        // Advanced only after the read succeeds, so a polling timeout replays nothing.
        val read = inputStream.read(buffer, 0, buffer.size)
        if (read == -1) {
            position = 0
            limit = 0
            throw EOFException()
        }
        position = 0
        limit = read
    }

    /** Whether a read would find something without waiting; over TLS, only what is already decrypted counts. */
    fun hasPending(): Boolean = position < limit || inputStream.available() > 0

    /** Reads one byte, refilling the buffer if it is spent. */
    fun readByte(): Byte {
        if (position >= limit) {
            fillBuffer()
        }
        return buffer[position++]
    }
    
    /** Reads a big-endian 32-bit integer. */
    fun readInt(): Int {
        ensure(4)
        val b1 = buffer[position++].toInt() and 0xFF
        val b2 = buffer[position++].toInt() and 0xFF
        val b3 = buffer[position++].toInt() and 0xFF
        val b4 = buffer[position++].toInt() and 0xFF
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }
    
    /** Reads a big-endian 16-bit integer. */
    fun readShort(): Short {
        ensure(2)
        val b1 = buffer[position++].toInt() and 0xFF
        val b2 = buffer[position++].toInt() and 0xFF
        return ((b1 shl 8) or b2).toShort()
    }

    /** Fills [bytes] with exactly [length] bytes, or fails at the end of the stream. */
    fun readFully(bytes: ByteArray, length: Int = bytes.size) {
        var offset = 0
        var remaining = length
        while (remaining > 0) {
            val available = limit - position
            if (available > 0) {
                val toCopy = minOf(available, remaining)
                System.arraycopy(buffer, position, bytes, offset, toCopy)
                position += toCopy
                offset += toCopy
                remaining -= toCopy
            }
            if (remaining > 0) {
                fillBuffer()
            }
        }
    }

    /** Reads past exactly [length] bytes without an array for them. */
    fun skip(length: Int) {
        var remaining = length
        while (remaining > 0) {
            val available = limit - position
            if (available > 0) {
                val toSkip = minOf(available, remaining)
                position += toSkip
                remaining -= toSkip
            }
            if (remaining > 0) {
                fillBuffer()
            }
        }
    }

    /** Reads exactly [length] bytes. */
    fun readBytes(length: Int): ByteArray {
        val array = ByteArray(length)
        readFully(array)
        return array
    }

    /** Reads a zero-terminated UTF-8 string. */
    fun readCString(): String {
        var length = 0
        while (true) {
            if (position >= limit) {
                fillBuffer()
            }
            
            var nullIndex = -1
            for (i in position until limit) {
                if (buffer[i] == 0.toByte()) {
                    nullIndex = i
                    break
                }
            }

            if (nullIndex != -1) {
                val chunkLen = nullIndex - position
                ensureStringBuffer(length + chunkLen)
                System.arraycopy(buffer, position, stringBuffer, length, chunkLen)
                length += chunkLen
                position = nullIndex + 1
                break
            } else {
                val chunkLen = limit - position
                ensureStringBuffer(length + chunkLen)
                System.arraycopy(buffer, position, stringBuffer, length, chunkLen)
                length += chunkLen
                position = limit
            }
        }
        return String(stringBuffer, 0, length, StandardCharsets.UTF_8)
    }

    private fun ensureStringBuffer(neededSize: Int) {
        if (neededSize > stringBuffer.size) {
            var newSize = stringBuffer.size * 2
            while (newSize < neededSize) {
                newSize *= 2
            }
            val newBuffer = ByteArray(newSize)
            System.arraycopy(stringBuffer, 0, newBuffer, 0, stringBuffer.size)
            stringBuffer = newBuffer
        }
    }
}
