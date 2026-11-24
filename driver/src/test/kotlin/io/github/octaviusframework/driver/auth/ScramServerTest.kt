package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.authenticationOk
import io.github.octaviusframework.driver.message
import io.github.octaviusframework.driver.readyForQuery
import io.github.octaviusframework.driver.writeCString
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.StartupMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the driver makes of a SCRAM server that breaks the exchange in ways a real PostgreSQL never does: parameters
 * no key can be derived with, or a final signature that proves nothing.
 *
 * The server here stores no key for the role: it plays the exchange through, collects the client's proof, and
 * waves the login in with a signature it could not have computed.
 */
class ScramServerTest {

    /**
     * Accepts one SCRAM login, opens it with [serverFirst] made from the client's nonce, answers the proof with a
     * made-up signature, and lets the client in regardless.
     */
    private class ImpostorServer(
        serverFirst: (clientNonce: String) -> String = { "r=${it}impostor,s=$SALT,i=4096" }
    ) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        private val worker = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    input.skipNBytes(input.readInt() - 4L)

                    output.write(message('R') { writeInt(10); writeCString("SCRAM-SHA-256"); writeByte(0) })
                    output.flush()

                    input.readByte()
                    input.readInt()
                    while (input.readByte().toInt() != 0) { /* the mechanism name */ }
                    val clientFirst = String(input.readNBytes(input.readInt()))
                    val clientNonce = clientFirst.substringAfter("r=")

                    output.write(message('R') { writeInt(11); write(serverFirst(clientNonce).toByteArray()) })
                    output.flush()

                    input.readByte()
                    input.skipNBytes(input.readInt() - 4L)

                    val signature = Base64.getEncoder().encodeToString(ByteArray(32))
                    output.write(message('R') { writeInt(12); write("v=$signature".toByteArray()) })
                    output.write(authenticationOk + readyForQuery)
                    output.flush()

                    while (input.read() != -1) { /* a Terminate, then the socket closed */ }
                }
            } catch (_: IOException) {
                // The client dropped the socket, or the test is over and closed ours
            }
        }

        override fun close() {
            serverSocket.close()
            worker.join(5_000)
        }
    }

    private fun loginRefusedBy(server: ImpostorServer): InitializationException =
        PgStream("localhost", server.port, InetAddress.getLoopbackAddress()).use { stream ->
            stream.writeMessage(StartupMessage(mapOf("user" to "postgres", "database" to "senatus")))
            stream.flush()

            assertThrows<InitializationException> {
                Authenticator.authenticate(
                    stream, "1234", ChannelBinding.PREFER, null,
                    OAuthTokenRequest("localhost", server.port, "postgres", "senatus")
                )
            }
        }

    @Test
    fun `refuses a server whose final signature does not prove it knows the password`() {
        val ex = ImpostorServer().use { loginRefusedBy(it) }

        assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, ex.reason)
        assertTrue(ex.details!!.contains("server signature"), ex.details)
    }

    @Test
    fun `refuses an iteration count below one as a protocol violation`() {
        val ex = ImpostorServer { "r=${it}impostor,s=$SALT,i=0" }.use { loginRefusedBy(it) }

        assertEquals(InitializationExceptionReason.PROTOCOL_VIOLATION, ex.reason)
        assertTrue(ex.details!!.contains("Iteration count"), ex.details)
    }

    @Test
    fun `refuses an empty salt as a protocol violation`() {
        val ex = ImpostorServer { "r=${it}impostor,s=,i=4096" }.use { loginRefusedBy(it) }

        assertEquals(InitializationExceptionReason.PROTOCOL_VIOLATION, ex.reason)
        assertTrue(ex.details!!.contains("Salt"), ex.details)
    }

    @Test
    fun `refuses a salt that is not base64 as a protocol violation`() {
        val ex = ImpostorServer { "r=${it}impostor,s=sal*,i=4096" }.use { loginRefusedBy(it) }

        assertEquals(InitializationExceptionReason.PROTOCOL_VIOLATION, ex.reason)
        assertTrue(ex.details!!.contains("Salt"), ex.details)
    }

    private companion object {
        val SALT: String = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
    }
}
