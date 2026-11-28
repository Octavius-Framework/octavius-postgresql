package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.ScriptedServer
import io.github.octaviusframework.driver.auth.Authenticator
import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.auth.OAuthTokenRequest
import io.github.octaviusframework.driver.authenticationOk
import io.github.octaviusframework.driver.backendKey
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message
import io.github.octaviusframework.driver.message.frontend.StartupMessage
import io.github.octaviusframework.driver.negotiate
import io.github.octaviusframework.driver.parameter
import io.github.octaviusframework.driver.readyForQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the driver does with the answer to its startup message.
 *
 * A real PostgreSQL 18 never negotiates and always reports `server_version` and `search_path`, and a real PgBouncer
 * is a job of its own in CI - so the answers neither gives on demand are played here by a server that sends only the
 * messages each case is about.
 */
class ProtocolNegotiationTest {

    /** Runs the login against [script] and hands [check] the stream it left, for what it recorded. */
    private fun login(script: ByteArray, check: (PgStream, ScriptedServer) -> Unit) {
        ScriptedServer(script).use { server ->
            PgStream("localhost", server.port, InetAddress.getLoopbackAddress()).use { stream ->
                stream.writeMessage(StartupMessage(mapOf("user" to "postgres", "database" to "senatus")))
                stream.flush()
                Authenticator.authenticate(
                    stream, null, ChannelBinding.PREFER, null,
                    OAuthTokenRequest("localhost", server.port, "postgres", "senatus")
                )
                check(stream, server)
            }
        }
    }

    // Bounded by socketTimeout: a login let through would wait forever on catalog queries the script never answers.
    private fun connect(script: ByteArray): InitializationException =
        ScriptedServer(script).use { server ->
            assertThrows<InitializationException> {
                OctaviusConnectionFactory.createConnection(
                    "jdbc:octavius://localhost:${server.port}/senatus?sslmode=disable&socketTimeout=5"
                )
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
                    stream.writeMessage(StartupMessage(mapOf("user" to "postgres")))
                    stream.flush()
                    assertThrows<InitializationException> {
                        Authenticator.authenticate(
                            stream, null, ChannelBinding.PREFER, null,
                            OAuthTokenRequest("localhost", server.port, "postgres", "postgres")
                        )
                    }
                }
            }

            assertEquals(InitializationExceptionReason.PROTOCOL_VIOLATION, ex.reason)
            assertTrue(ex.details!!.contains("${version ushr 16}.${version and 0xFFFF}"), ex.details)
        }
    }

    @Test
    fun `should refuse a login that did not report server_version or search_path`() {
        // PostgreSQL 18 behind a proxy that does not pass one of them on. Without the version, a server older than
        // 18 would get in; without the search path, type names would be resolved against a guess.
        val reported = mapOf("server_version" to "18.6", "search_path" to "\"\$user\", public")
        for (missing in reported.keys) {
            val parameters = reported.filterKeys { it != missing }.entries
                .fold(ByteArray(0)) { bytes, (name, value) -> bytes + parameter(name, value) }
            val ex = connect(negotiate(0x0003_0000) + authenticationOk + parameters + backendKey(4) + readyForQuery)

            assertEquals(InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER, ex.reason, "Without $missing")
            assertTrue(ex.details!!.contains(missing), ex.details)
        }
    }
}
