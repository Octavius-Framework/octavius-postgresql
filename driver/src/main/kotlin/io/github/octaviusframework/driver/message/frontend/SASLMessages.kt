package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream
import java.nio.charset.StandardCharsets

/** `SASLInitialResponse`, naming the mechanism. */
internal class SASLInitialResponse(private val mechanism: String, private val clientFirstMessage: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val mechBytes = mechanism.toByteArray(StandardCharsets.UTF_8)
        val dataBytes = clientFirstMessage.toByteArray(StandardCharsets.UTF_8)

        // Message size = 4 + mechanism size (with null) + 4 (data size) + data size
        val length = 4 + mechBytes.size + 1 + 4 + dataBytes.size

        out.writeByte('p'.code.toByte())
        out.writeInt(length)
        out.writeCString(mechanism)
        out.writeInt(dataBytes.size)
        out.writeBytes(dataBytes)
    }
}

/** `SASLResponse`, carrying the client-final-message. */
internal class SASLResponse(private val clientFinalMessage: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val dataBytes = clientFinalMessage.toByteArray(StandardCharsets.UTF_8)
        val length = 4 + dataBytes.size

        out.writeByte('p'.code.toByte())
        out.writeInt(length)
        out.writeBytes(dataBytes)
    }
}

/** The cleartext password, in answer to `AuthenticationCleartextPassword`; sent only over TLS. */
internal class PasswordMessage(private val password: String) : FrontendMessage {
    override fun encode(out: PgOutputStream) {
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        val length = 4 + passwordBytes.size + 1

        out.writeByte('p'.code.toByte())
        out.writeInt(length)
        out.writeCString(password)
    }
}
