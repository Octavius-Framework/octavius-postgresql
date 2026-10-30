package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.initialization.authenticationOk
import io.github.octaviusframework.driver.initialization.message
import io.github.octaviusframework.driver.initialization.readyForQuery
import io.github.octaviusframework.driver.initialization.writeCString
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
 * What the driver makes of a SCRAM server that cannot prove it knows the password.
 *
 * A real PostgreSQL always signs its final message with the key it stores for the role, so the server here is
 * one that has none: it plays the exchange through, collects the client's proof, and waves the login in with a
 * signature it could not have computed.
 */
class ScramServerSignatureTest {

    /** Accepts one SCRAM login, answers it with a made-up signature, and lets the client in regardless. */
    private class ImpostorServer : AutoCloseable {
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

                    val salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
                    output.write(message('R') { writeInt(11); write("r=${clientNonce}impostor,s=$salt,i=4096".toByteArray()) })
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

    @Test
    fun `refuses a server whose final signature does not prove it knows the password`() {
        ImpostorServer().use { server ->
            PgStream("localhost", server.port, InetAddress.getLoopbackAddress()).use { stream ->
                stream.sendMessage(StartupMessage(mapOf("user" to "postgres", "database" to "senatus")))
                stream.flush()

                val ex = assertThrows<InitializationException> {
                    Authenticator.authenticate(stream, "1234", ChannelBinding.PREFER)
                }

                assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, ex.reason)
                assertTrue(ex.details!!.contains("server signature"), ex.details)
            }
        }
    }
}
