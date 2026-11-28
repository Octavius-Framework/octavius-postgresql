package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.initialization.OctaviusConnectionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the driver puts on the wire to start TLS, read by servers that only listen: a PostgreSQL 18 takes both ways of
 * starting it and says nothing about which one came.
 */
class SslNegotiationTest {

    /**
     * Takes one client and keeps the first TLS record it sends, answering an `SSLRequest` before it with `S`, then
     * hangs up, so no handshake gets past the ClientHello. Its fields are read after [close], which waits for it.
     */
    private class ClientHelloCatcher : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        @Volatile
        var sslRequested = false

        @Volatile
        var clientHello: String? = null

        private val worker = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    var contentType = input.readUnsignedByte()
                    if (contentType != HANDSHAKE_RECORD) {
                        // The rest of the 8-byte SSLRequest.
                        input.skipNBytes(7)
                        sslRequested = true
                        socket.getOutputStream().apply { write('S'.code); flush() }
                        contentType = input.readUnsignedByte()
                    }
                    if (contentType == HANDSHAKE_RECORD) {
                        input.readUnsignedShort()
                        val record = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                        // Names in a ClientHello are ASCII, which this keeps byte for byte.
                        clientHello = String(record, Charsets.ISO_8859_1)
                    }
                }
            } catch (_: IOException) {
                // The client dropped the socket, or the test is over and closed ours
            }
        }

        override fun close() {
            serverSocket.close()
            worker.join(5_000)
        }

        companion object {
            const val HANDSHAKE_RECORD = 0x16
        }
    }

    @Test
    fun `under sslnegotiation=direct the ClientHello is the first thing sent, and it offers ALPN postgresql`() {
        val server = ClientHelloCatcher()
        val exception = server.use {
            assertThrows<InitializationException> {
                OctaviusConnectionFactory.createConnection(
                    "jdbc:octavius://localhost:${server.port}/curia?sslmode=require&sslnegotiation=direct"
                )
            }
        }

        assertFalse(server.sslRequested, "No SSLRequest should come before a direct handshake")
        val clientHello = assertNotNull(server.clientHello, "A TLS record should have come first")
        assertTrue(clientHello.contains("postgresql"), "The ClientHello should offer ALPN postgresql")
        // The server hung up mid-handshake, as a pooler that expects an SSLRequest does.
        assertEquals(InitializationExceptionReason.SSL_ERROR, exception.reason)
        assertTrue(exception.details!!.contains("sslnegotiation=postgres"), exception.details)
    }

    @Test
    fun `sslnegotiation=direct below sslmode=require is refused before connecting`() {
        // Nothing listens there, so a driver that tried to connect would fail with CONNECTION_ERROR instead.
        val base = "jdbc:octavius://localhost:1/curia?sslnegotiation=direct"
        for (url in listOf(base, "$base&sslmode=prefer", "$base&sslmode=disable")) {
            val exception = assertThrows<InvalidOperationException>(url) {
                OctaviusConnectionFactory.createConnection(url)
            }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, exception.reason)
            assertTrue(exception.details!!.contains("sslmode=require"), exception.details)
        }
    }

    @Test
    fun `a direct handshake completed without ALPN postgresql is refused`(@TempDir dir: Path) {
        val password = "senatus".toCharArray()
        val keyStorePath = dir.resolve("curia.p12")
        val bin = Path.of(System.getProperty("java.home"), "bin")
        val keytool = bin.resolve("keytool.exe").takeIf { it.exists() } ?: bin.resolve("keytool")
        val keytoolRun = ProcessBuilder(
            keytool.toString(), "-genkeypair", "-alias", "curia", "-keyalg", "EC", "-dname", "CN=curia",
            "-keystore", keyStorePath.toString(), "-storetype", "PKCS12",
            "-storepass", String(password), "-keypass", String(password)
        ).redirectErrorStream(true).start()
        val keytoolOutput = keytoolRun.inputStream.bufferedReader().readText()
        assertEquals(0, keytoolRun.waitFor(), keytoolOutput)

        val keyStore = KeyStore.getInstance("PKCS12").apply { keyStorePath.inputStream().use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }

        // A TLS endpoint with no ALPN of its own: it completes the handshake and ignores what the client offered.
        val serverSocket = context.serverSocketFactory.createServerSocket(0)
        val worker = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { socket ->
                    (socket as SSLSocket).startHandshake()
                    socket.inputStream.read()
                }
            } catch (_: IOException) {
                // The client dropped the socket
            }
        }

        val exception = try {
            assertThrows<InitializationException> {
                OctaviusConnectionFactory.createConnection(
                    "jdbc:octavius://localhost:${serverSocket.localPort}/curia?sslmode=require&sslnegotiation=direct"
                )
            }
        } finally {
            serverSocket.close()
            worker.join(5_000)
        }

        assertEquals(InitializationExceptionReason.SSL_ERROR, exception.reason)
        assertTrue(exception.details!!.contains("ALPN"), exception.details)
    }
}
