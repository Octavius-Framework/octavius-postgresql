package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.auth.Authenticator
import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.jdbc.OctaviusConnectionFactory
import io.github.octaviusframework.driver.message.frontend.StartupMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the driver does with the answer to its startup message.
 *
 * A real PostgreSQL 18 never negotiates and always reports `search_path`, and a real PgBouncer is a job of its
 * own in CI - so the answers neither gives on demand are played here by a server that sends only the messages
 * each case is about.
 */
class ProtocolNegotiationTest {

    /** Runs the login against [script] and hands [check] the stream it left, for what it recorded. */
    private fun login(script: ByteArray, check: (PgStream, ScriptedServer) -> Unit) {
        ScriptedServer(script).use { server ->
            PgStream("localhost", server.port, InetAddress.getLoopbackAddress()).use { stream ->
                stream.sendMessage(StartupMessage(mapOf("user" to "postgres", "database" to "senatus")))
                stream.flush()
                Authenticator.authenticate(stream, null, ChannelBinding.PREFER)
                check(stream, server)
            }
        }
    }

    private fun connect(script: ByteArray): InitializationException =
        ScriptedServer(script).use { server ->
            assertThrows<InitializationException> {
                OctaviusConnectionFactory.createConnection("jdbc:octavius://localhost:${server.port}/senatus?sslmode=disable")
            }
        }

    @Test
    fun `should ask for 3_2 and take its longer cancel key when the server does not negotiate`() {
        login(authenticationOk + backendKey(32) + readyForQuery) { stream, server ->
            assertEquals(0x0003_0002, server.startupVersion)
            assertEquals(32, stream.secretKey.size)
        }
    }

    @Test
    fun `should carry on over 3_0 when the server names it`() {
        // What a pooler answers in front of PostgreSQL 18, and what PostgreSQL 17 answers itself.
        login(negotiate(0x0003_0000) + authenticationOk + backendKey(4) + readyForQuery) { stream, _ ->
            assertTrue(stream.startupComplete)
            assertEquals(4, stream.secretKey.size)
        }
    }

    @Test
    fun `should read a bare minor number the way the protocol documentation describes the field`() {
        login(negotiate(0) + authenticationOk + backendKey(4) + readyForQuery) { stream, _ ->
            assertTrue(stream.startupComplete)
        }
    }

    @Test
    fun `should refuse a version outside protocol 3 or newer than the one asked for`() {
        for (version in listOf(0x0004_0000, 0x0003_0003)) {
            val ex = ScriptedServer(negotiate(version) + authenticationOk + readyForQuery).use { server ->
                PgStream("localhost", server.port, InetAddress.getLoopbackAddress()).use { stream ->
                    stream.sendMessage(StartupMessage(mapOf("user" to "postgres")))
                    stream.flush()
                    assertThrows<InitializationException> {
                        Authenticator.authenticate(stream, null, ChannelBinding.PREFER)
                    }
                }
            }

            assertEquals(InitializationExceptionReason.PROTOCOL_VIOLATION, ex.reason)
            assertTrue(ex.details!!.contains("${version ushr 16}.${version and 0xFFFF}"), ex.details)
        }
    }

    @Test
    fun `should refuse a server older than 18 by the version it reports`() {
        val ex = connect(
            negotiate(0x0003_0000) + authenticationOk + parameter("server_version", "17.6") +
                backendKey(4) + readyForQuery
        )

        assertEquals(InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION, ex.reason)
        assertTrue(ex.details!!.contains("Received version: 17.6"), ex.details)
    }

    @Test
    fun `should refuse a login that did not report search_path`() {
        // PostgreSQL 18 behind a proxy that does not pass it on: the version is right, the parameter the
        // version was required for never arrives.
        val ex = connect(
            negotiate(0x0003_0000) + authenticationOk + parameter("server_version", "18.6") +
                backendKey(4) + readyForQuery
        )

        assertEquals(InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER, ex.reason)
        assertTrue(ex.details!!.contains("search_path"), ex.details)
    }
}
