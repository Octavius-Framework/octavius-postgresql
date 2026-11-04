package io.github.octaviusframework.driver.lo

import java.io.OutputStream

/** Writes a [LargeObject]; closing it closes the object. */
class LargeObjectOutputStream(private val obj: LargeObject) : OutputStream() {

    override fun write(b: Int) {
        val byteArray = byteArrayOf(b.toByte())
        obj.write(byteArray, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        obj.write(b, off, len)
    }

    override fun close() {
        obj.close()
    }
}
