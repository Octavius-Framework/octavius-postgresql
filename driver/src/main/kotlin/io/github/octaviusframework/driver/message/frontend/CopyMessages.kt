package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream

/** `CopyData` from the client. */
internal class FrontendCopyDataMessage(
    private val data: ByteArray,
    private val offset: Int = 0,
    private val length: Int = data.size
) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('d'.code.toByte())
        out.writeInt(4 + length)
        out.writeBytes(data, offset, length)
    }
}

/** `CopyDone` from the client. */
internal class FrontendCopyDoneMessage : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('c'.code.toByte())
        out.writeInt(4)
    }
}

/** `CopyFail`, abandoning a COPY IN. */
internal class FrontendCopyFailMessage(private val errorMessage: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        out.writeByte('f'.code.toByte())
        val msgBytes = errorMessage.toByteArray(Charsets.UTF_8)
        out.writeInt(4 + msgBytes.size + 1)
        out.writeBytes(msgBytes)
        out.writeByte(0)
    }
}
