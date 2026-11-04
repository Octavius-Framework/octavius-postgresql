package io.github.octaviusframework.driver.io

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/** The largest array a JVM will hand out; the slack covers the header words a VM keeps for itself. */
private const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8

/**
 * The growable buffer a codec writes its binary value into, big-endian, with room reserved for a length filled in
 * afterwards.
 */
class PgByteWriter(
    private val initialCapacity: Int = 1024,
    private val maxCapacity: Int = 65536
) {
    var data = ByteArray(initialCapacity)
        private set
    var position = 0
        private set

    /** Grows the buffer so that [needed] more bytes fit. */
    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size - position) return

        val required = position.toLong() + needed
        if (required > MAX_ARRAY_SIZE) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "Parameter buffer would have to hold $required bytes, past the largest array a JVM hands out ($MAX_ARRAY_SIZE)"
            )
        }
        val newCapacity = (data.size.toLong() * 2).coerceIn(required, MAX_ARRAY_SIZE.toLong())
        data = data.copyOf(newCapacity.toInt())
    }

    /** Appends [b]. */
    fun writeByte(b: Byte) {
        ensureCapacity(1)
        data[position++] = b
    }

    /** Appends [i], big-endian. */
    fun writeInt(i: Int) {
        ensureCapacity(4)
        data[position++] = (i shr 24).toByte()
        data[position++] = (i shr 16).toByte()
        data[position++] = (i shr 8).toByte()
        data[position++] = i.toByte()
    }

    /** Appends [s], big-endian. */
    fun writeShort(s: Short) {
        ensureCapacity(2)
        val i = s.toInt()
        data[position++] = (i shr 8).toByte()
        data[position++] = i.toByte()
    }

    /** Appends [l], big-endian. */
    fun writeLong(l: Long) {
        ensureCapacity(8)
        data[position++] = (l shr 56).toByte()
        data[position++] = (l shr 48).toByte()
        data[position++] = (l shr 40).toByte()
        data[position++] = (l shr 32).toByte()
        data[position++] = (l shr 24).toByte()
        data[position++] = (l shr 16).toByte()
        data[position++] = (l shr 8).toByte()
        data[position++] = l.toByte()
    }

    /** Appends [f] as its IEEE 754 bits. */
    fun writeFloat(f: Float) {
        writeInt(f.toRawBits())
    }

    /** Appends [d] as its IEEE 754 bits. */
    fun writeDouble(d: Double) {
        writeLong(d.toRawBits())
    }

    /** Appends [bytes]. */
    fun writeBytes(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(data, position)
        position += bytes.size
    }

    /** Reserves 4 bytes for a length and returns where they are, for [fillLengthInt]. */
    fun reserveLengthInt(): Int {
        val marker = position
        writeInt(0) // placeholder
        return marker
    }

    /** Writes at [markerIndex] how many bytes followed the 4 [reserveLengthInt] reserved. */
    fun fillLengthInt(markerIndex: Int) {
        val length = position - markerIndex - 4
        data[markerIndex] = (length shr 24).toByte()
        data[markerIndex + 1] = (length shr 16).toByte()
        data[markerIndex + 2] = (length shr 8).toByte()
        data[markerIndex + 3] = length.toByte()
    }

    /** A copy of the bytes written so far; [data] is longer and reused. */
    fun toByteArray(): ByteArray {
        return data.copyOfRange(0, position)
    }

    /** Moves the write cursor to [newPosition], clearing nothing. */
    fun updatePosition(newPosition: Int) {
        position = newPosition
    }

    /** Empties the buffer, releasing a backing array that grew past `maxCapacity`. */
    fun clear() {
        position = 0
        if (data.size > maxCapacity) {
            data = ByteArray(initialCapacity)
        }
    }
}