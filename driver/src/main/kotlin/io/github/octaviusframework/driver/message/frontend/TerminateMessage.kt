package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream

/** `Terminate`. */
internal class TerminateMessage : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('X'.code.toByte())
        out.writeInt(4)
    }
}
