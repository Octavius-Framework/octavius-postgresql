package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream

/** `Query`: [query] through the simple protocol. */
internal class SimpleQueryMessage(private val query: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val queryBytes = query.toByteArray(Charsets.UTF_8)
        val length = 4 + queryBytes.size + 1 // length itself + bytes + null terminator

        out.writeByte('Q'.code.toByte())
        out.writeInt(length)
        out.writeBytes(queryBytes)
        out.writeByte(0)
    }
}