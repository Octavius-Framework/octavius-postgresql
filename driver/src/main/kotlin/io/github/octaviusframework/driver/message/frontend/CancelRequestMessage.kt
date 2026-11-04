package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream

/** `CancelRequest` for the backend [processId], with its [secretKey]. */
internal class CancelRequestMessage(private val processId: Int, private val secretKey: ByteArray) : FrontendMessage {

    override fun encode(out: PgOutputStream) {
        val len = 12 + secretKey.size
        out.writeInt(len) // variable length
        out.writeInt(80877102) // cancel request code
        out.writeInt(processId)
        out.writeBytes(secretKey)
    }
}
