package io.github.octaviusframework.driver.io

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * A highly optimized, buffered input stream tailored for the PostgreSQL wire protocol.
 *
 * It provides specialized methods for efficiently reading protocol primitives
 * like bytes, integers, shorts, and null-terminated C-style strings.
 * Internal buffering is used to minimize network reads and object allocations.
 */
internal class PgInputStream(private var inputStream: InputStream) {
    private val buffer = ByteArray(8192)
    private var position = 0
    private var limit = 0
    
    // We use this buffer to reduce allocations when reading C-style strings
    private var stringBuffer = ByteArray(256)

    /**
     * Swaps the underlying stream, discarding whatever is still buffered from the old one.
     *
     * Discarding is the point: this is how the TLS upgrade takes over a connection, and bytes read before
     * the handshake belong to the plaintext stream that no longer exists.
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
        // The buffer state is only advanced once the read has actually succeeded. Resetting
        // position beforehand would, on a SocketTimeoutException - which polling hits on every
        // idle tick - leave position at 0 with the previous limit intact, replaying bytes that
        // were already consumed and desynchronizing every later exchange on the connection.
        val read = inputStream.read(buffer, 0, buffer.size)
        if (read == -1) {
            position = 0
            limit = 0
            throw EOFException()
        }
        position = 0
        limit = read
    }

    /**
     * Whether a read would find something without waiting: bytes left in the buffer, or bytes the underlying
     * stream already holds.
     *
     * The underlying stream's own count is what `available()` makes of it - on a plain socket what the kernel
     * has received, on a TLS one only what has already been decrypted.
     */
    fun hasPending(): Boolean = position < limit || inputStream.available() > 0

    /** Reads one byte, refilling the buffer if it is spent. */
    fun readByte(): Byte {
        if (position >= limit) {
            fillBuffer()
        }
        return buffer[position++]
    }
    
    /** Reads a 32-bit integer, most significant byte first. */
    fun readInt(): Int {
        ensure(4)
        val b1 = buffer[position++].toInt() and 0xFF
        val b2 = buffer[position++].toInt() and 0xFF
        val b3 = buffer[position++].toInt() and 0xFF
        val b4 = buffer[position++].toInt() and 0xFF
        return (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
    }
    
    /** Reads a 16-bit integer, most significant byte first. */
    fun readShort(): Short {
        ensure(2)
        val b1 = buffer[position++].toInt() and 0xFF
        val b2 = buffer[position++].toInt() and 0xFF
        return ((b1 shl 8) or b2).toShort()
    }

    /**
     * Fills [bytes] with exactly [length] bytes, reading as many times as it takes.
     *
     * A protocol message has a declared length, so a short read is never an answer - this loops until it
     * has them all or the stream ends.
     */
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

    /**
     * Advances past exactly [length] bytes without materializing them.
     *
     * The bytes still have to leave the stream - a payload left in it is read as the next message's
     * tag by everything that comes after - but a payload nothing will look at needs no array to be
     * read into, which is the only part [readBytes] would add.
     */
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

    /**
     * Returns exactly the specified number of bytes.
     */
    fun readBytes(length: Int): ByteArray {
        val array = ByteArray(length)
        readFully(array)
        return array
    }

    /**
     * Reads a null-terminated string.
     */
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
