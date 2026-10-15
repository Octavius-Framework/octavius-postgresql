package io.github.octaviusframework.driver.io

import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.SessionTerminatedExceptionReason
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.message.frontend.FrontendCopyDataMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the driver makes of a session the server ends.
 *
 * The server's side of it is not a thing a real PostgreSQL does on demand in every shape - a `FATAL` followed by
 * a connection that stays open, or by a reset rather than a close - so these are played by a server that sends
 * exactly the bytes each case is about. What a real server does is in `SessionTerminatedExceptionIntegrationTest`.
 */
class SessionTerminationProtocolTest {

    /** Accepts one connection and hands it to [conversation]; whatever it throws is kept for the test to check. */
    private class ScriptedServer(private val conversation: (Socket) -> Unit) : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        val port: Int = serverSocket.localPort

        private val worker = thread(isDaemon = true) {
            try {
                serverSocket.accept().use { conversation(it) }
            } catch (_: IOException) {
                // The client dropped the socket, or the test is over and closed ours
            }
        }

        override fun close() {
            serverSocket.close()
            worker.join(5_000)
        }
    }

    private fun message(type: Char, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            DataOutputStream(out).run {
                writeByte(type.code)
                writeInt(payload.size + 4)
                write(payload)
            }
        }.toByteArray()
    }

    private fun DataOutputStream.writeCString(value: String) {
        write(value.toByteArray())
        writeByte(0)
    }

    private fun error(severity: String, code: String, text: String) = message('E') {
        for ((field, value) in listOf('S' to severity, 'V' to severity, 'C' to code, 'M' to text)) {
            writeByte(field.code)
            writeCString(value)
        }
        writeByte(0)
    }

    private val administratorCommand = error("FATAL", "57P01", "terminating connection due to administrator command")
    private val idleSessionTimeout = error("FATAL", "57P05", "terminating connection due to idle-session timeout")
    private val transactionTimeout = error("FATAL", "25P04", "terminating connection due to transaction timeout")

    private fun Socket.send(bytes: ByteArray) = getOutputStream().apply { write(bytes); flush() }

    /** Reads one frontend message off [socket] - what a statement sent by the executor arrives as. */
    private fun Socket.receiveMessage() {
        val input = DataInputStream(getInputStream())
        input.readByte()
        input.skipNBytes(input.readInt() - 4L)
    }

    /**
     * A stream past login, as every stream a session holds is. A read timeout bounds what would otherwise be a
     * wait for a close that never comes - the thing some of these cases are about.
     */
    private fun loggedIn(port: Int): PgStream =
        PgStream("localhost", port, InetAddress.getLoopbackAddress()).also {
            it.startupComplete = true
            it.networkTimeout = 5_000
        }

    private fun executorOver(stream: PgStream) = QueryExecutor(stream, null, null, logParameterValues = false)

    /** Waits for what the server sent to arrive, so a case about an idle session is not a race with it. */
    private fun awaitInput(stream: PgStream) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!stream.inputStream.hasPending()) {
            if (System.nanoTime() > deadline) fail("the scripted server's message never arrived")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a FATAL ends the exchange on the message, without waiting for the connection to close`() {
        val released = CountDownLatch(1)
        ScriptedServer { socket ->
            socket.receiveMessage()
            socket.send(administratorCommand)
            // No ReadyForQuery follows a FATAL, and here no close does either: a driver that waited for one
            // would sit on the read until its timeout.
            released.await(10, TimeUnit.SECONDS)
        }.use { server ->
            loggedIn(server.port).use { stream ->
                val ex = assertThrows<SessionTerminatedException> { executorOver(stream).execute("SELECT 1") }

                assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
                assertEquals("57P01", ex.sqlState)
                assertTrue(stream.isBroken)
                assertEquals("57P01", stream.terminatedBy?.code)
            }
            released.countDown()
        }
    }

    @Test
    fun `a FATAL followed by a reset is reported as the FATAL, not as the reset`() {
        ScriptedServer { socket ->
            socket.receiveMessage()
            socket.send(transactionTimeout)
            // Then an abortive close: the read after the FATAL is a reset rather than an end of stream, which
            // is what a client that writes to a backend already gone gets back. Not at once, since an abortive
            // close throws away whatever is still unsent, and the FATAL is the point.
            Thread.sleep(200)
            socket.setSoLinger(true, 0)
        }.use { server ->
            loggedIn(server.port).use { stream ->
                val ex = assertThrows<SessionTerminatedException> { executorOver(stream).execute("SELECT 1") }

                assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
                assertEquals("25P04", ex.sqlState)
            }
        }
    }

    @Test
    fun `a session ended while idle is found before anything is written to it`() {
        val written = CountDownLatch(1)
        var received = -1
        ScriptedServer { socket ->
            socket.send(idleSessionTimeout)
            received = socket.getInputStream().readAllBytes().size
            written.countDown()
        }.use { server ->
            loggedIn(server.port).use { stream ->
                awaitInput(stream)

                val ex = assertThrows<SessionTerminatedException> { executorOver(stream).execute("SELECT 1") }

                assertEquals(SessionTerminatedExceptionReason.IDLE_SESSION_TIMEOUT, ex.reason)
                // Found before the write, not recovered after a failed one
                assertTrue(ex.suppressed.isEmpty(), "expected no write failure behind it, got ${ex.suppressed.toList()}")
            }
            assertTrue(written.await(5, TimeUnit.SECONDS))
            // Nothing sent at a backend that has already exited, so nothing for its host to answer with a reset
            assertEquals(0, received)
        }
    }

    @Test
    fun `what the server sent between exchanges is taken in on the way into the next one`() {
        ScriptedServer { socket ->
            socket.send(
                message('A') { writeInt(4242); writeCString("forum"); writeCString("the Senate convenes") } +
                    message('N') {
                        for ((field, value) in listOf('S' to "NOTICE", 'V' to "NOTICE", 'C' to "00000", 'M' to "a word")) {
                            writeByte(field.code)
                            writeCString(value)
                        }
                        writeByte(0)
                    } +
                    message('S') { writeCString("TimeZone"); writeCString("Europe/Rome") }
            )
            socket.receiveMessage()
            socket.send(message('I') {} + message('Z') { writeByte('I'.code) })
            socket.getInputStream().readAllBytes()
        }.use { server ->
            loggedIn(server.port).use { stream ->
                awaitInput(stream)

                executorOver(stream).execute("")

                assertEquals("Europe/Rome", stream.parameters["TimeZone"])
                assertTrue(!stream.isBroken)
            }
        }
    }

    @Test
    @DisabledOnOs(
        OS.WINDOWS,
        disabledReason = "Winsock discards what the client had received but not read once the reset arrives, so the FATAL is gone before the failed write can read it"
    )
    fun `a write that fails on a session the server ended reports the FATAL that explains it`() {
        ScriptedServer { socket ->
            socket.send(transactionTimeout)
            // Closed with the client's bytes still arriving, so its host answers them with a reset and the
            // client's next write fails - a COPY IN streaming into a transaction the server has timed out.
        }.use { server ->
            loggedIn(server.port).use { stream ->
                awaitInput(stream)

                // No exchange around it, so nothing looks for the FATAL before writing: only the failed write can
                // find it. Large enough that some write lands after the reset.
                val ex = assertThrows<SessionTerminatedException> {
                    stream.sendMessage(FrontendCopyDataMessage(ByteArray(32 * 1024 * 1024)))
                    stream.flush()
                }

                assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
                assertTrue(ex.suppressed.single() is IOException, "the failed write rides along: ${ex.suppressed.toList()}")
                assertTrue(stream.isBroken)
            }
        }
    }

    @Test
    fun `a connection that closes without a word is still a NetworkException`() {
        ScriptedServer { socket ->
            socket.receiveMessage()
        }.use { server ->
            loggedIn(server.port).use { stream ->
                val ex = assertThrows<NetworkException> { executorOver(stream).execute("SELECT 1") }

                assertTrue(
                    ex.reason == NetworkExceptionReason.CONNECTION_CLOSED_BY_PEER ||
                        ex.reason == NetworkExceptionReason.CONNECTION_ERROR,
                    "expected the connection reported as lost, got ${ex.reason}"
                )
                assertEquals(null, stream.terminatedBy)
            }
        }
    }
}
