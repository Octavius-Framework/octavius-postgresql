package io.github.octaviusframework.driver.io

/** The big-endian 16-bit integer at [offset]. */
fun ByteArray.getShortBE(offset: Int = 0): Short {
    return ((this[offset].toInt() and 0xFF shl 8) or
            (this[offset + 1].toInt() and 0xFF)).toShort()
}

/** The big-endian 32-bit integer at [offset]. */
fun ByteArray.getIntBE(offset: Int = 0): Int {
    return (this[offset].toInt() and 0xFF shl 24) or
           (this[offset + 1].toInt() and 0xFF shl 16) or
           (this[offset + 2].toInt() and 0xFF shl 8) or
           (this[offset + 3].toInt() and 0xFF)
}

/** The big-endian 64-bit integer at [offset]. */
fun ByteArray.getLongBE(offset: Int = 0): Long {
    return (this[offset].toLong() and 0xFF shl 56) or
           (this[offset + 1].toLong() and 0xFF shl 48) or
           (this[offset + 2].toLong() and 0xFF shl 40) or
           (this[offset + 3].toLong() and 0xFF shl 32) or
           (this[offset + 4].toLong() and 0xFF shl 24) or
           (this[offset + 5].toLong() and 0xFF shl 16) or
           (this[offset + 6].toLong() and 0xFF shl 8) or
           (this[offset + 7].toLong() and 0xFF)
}

/** The `float4` at [offset], from its IEEE 754 bits. */
fun ByteArray.getFloatBE(offset: Int = 0): Float {
    return Float.fromBits(this.getIntBE(offset))
}

/** The `float8` at [offset], from its IEEE 754 bits. */
fun ByteArray.getDoubleBE(offset: Int = 0): Double {
    return Double.fromBits(this.getLongBE(offset))
}
