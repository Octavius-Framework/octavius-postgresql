package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.assumeUnreadFatalSurvives
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * A session the server ends reaches the caller as the reason the server gave, not as the closed socket that
 * followed it.
 *
 * Each case waits for the backend to be gone from `pg_stat_activity` before looking, so none of them races the
 * server: by the time a backend has left the view, it has sent its `FATAL`.
 */
class SessionTerminatedExceptionIntegrationTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val schema = "CREATE TABLE census (id int, cognomen text);"

    private fun OctaviusSession.backendPid(): Int = createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()

    private fun terminate(pid: Int) {
        openSession().use { it.createNativeQuery("SELECT pg_terminate_backend($1)").fetchFieldStrict<Boolean>(pid) }
    }

    /** Polls `pg_stat_activity` from a session of its own until [condition] holds for [pid]'s row. */
    private fun awaitBackend(pid: Int, what: String, condition: (state: String?) -> Boolean) {
        openSession().use { observer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val state = observer.createNativeQuery("SELECT coalesce(state, 'none') FROM pg_stat_activity WHERE pid = $1")
                    .fetchField<String?>(pid)
                if (condition(state)) return
                if (System.nanoTime() > deadline) fail("backend $pid never became $what")
                Thread.sleep(20)
            }
        }
    }

    private fun awaitGone(pid: Int) = awaitBackend(pid, "gone") { it == null }

    @Test
    fun `a session terminated while idle reports the administrator command and is gone afterwards`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            val pid = session.backendPid()
            terminate(pid)
            awaitGone(pid)

            val ex = assertFailsWith<SessionTerminatedException> {
                session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
            }
            logger.error(ex) { "" }
            assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
            assertEquals("57P01", ex.sqlState)
            assertEquals("FATAL", ex.serverErrorMessage?.severity)
            assertNotNull(ex.queryContext, "the statement that found it is named")

            // Nothing more reaches a server, and what is asked next says why the connection is gone
            val next = assertFailsWith<NetworkException> {
                session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
            }
            assertEquals(NetworkExceptionReason.CONNECTION_ERROR, next.reason)
            assertTrue(next.details!!.contains("57P01"), "expected the server's reason in: ${next.details}")
            assertFalse(session.isValid(1))
        }
    }

    @Test
    fun `a session terminated mid-statement reports the administrator command`() {
        openSession().use { session ->
            val pid = session.backendPid()
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    awaitBackend(pid, "active") { it == "active" }
                    terminate(pid)
                }

                val ex = assertFailsWith<SessionTerminatedException> {
                    session.createNativeQuery("SELECT pg_sleep(10)").fetchRows()
                }
                assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `idle_in_transaction_session_timeout ends the session, and the block reports that rather than its rollback`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            session.createNativeQuery("SET idle_in_transaction_session_timeout = '100ms'").execute()
            val pid = session.backendPid()

            val ex = assertFailsWith<SessionTerminatedException> {
                session.transaction.required {
                    createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
                    awaitGone(pid) // sitting in the transaction with nothing running, past the timeout
                    createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
                }
            }
            assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
            assertEquals("25P03", ex.sqlState)
            // The rollback and the restore after it found the connection gone; they ride along, underneath
            assertTrue(ex.suppressed.isNotEmpty() && ex.suppressed.all { it is NetworkException }, "${ex.suppressed.toList()}")
        }
    }

    @Test
    fun `transaction_timeout ends the session while a statement runs`() {
        openSession().use { session ->
            val ex = assertFailsWith<SessionTerminatedException> {
                session.transaction.required(transactionTimeout = 200.milliseconds) {
                    createNativeQuery("SELECT pg_sleep(10)").fetchRows()
                }
            }
            assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
            assertEquals("25P04", ex.sqlState)
        }
    }

    @Test
    fun `transaction_timeout ends the session while it sits idle in the transaction`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            val pid = session.backendPid()

            val ex = assertFailsWith<SessionTerminatedException> {
                session.transaction.required(transactionTimeout = 200.milliseconds) {
                    // The BEGIN, and the timeout with it, go out with the first statement
                    createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
                    awaitGone(pid)
                    createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
                }
            }
            assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
        }
    }

    @Test
    fun `idle_session_timeout ends an idle session`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            session.createNativeQuery("SET idle_session_timeout = '100ms'").execute()
            val pid = session.backendPid()
            awaitGone(pid)

            val ex = assertFailsWith<SessionTerminatedException> {
                session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
            }
            assertEquals(SessionTerminatedExceptionReason.IDLE_SESSION_TIMEOUT, ex.reason)
            assertEquals("57P05", ex.sqlState)
        }
    }

    @Test
    fun `a statement cancelled by statement_timeout leaves the session usable, unlike a terminated one`() {
        openSession().use { session ->
            session.createNativeQuery("SET statement_timeout = '100ms'").execute()

            val ex = assertFailsWith<ExecutionAbortedException> {
                session.createNativeQuery("SELECT pg_sleep(10)").fetchRows()
            }
            assertEquals("57014", ex.sqlState)

            session.createNativeQuery("SET statement_timeout = 0").execute()
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `a large statement sent at a session already ended is not sent at all`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            val pid = session.backendPid()
            terminate(pid)
            awaitGone(pid)

            // Megabytes of parameter would meet the reset the server's host answers with halfway through the
            // write. The termination is read before the first byte goes out instead.
            val ex = assertFailsWith<SessionTerminatedException> {
                session.createNativeQuery("SELECT length($1)").fetchFieldStrict<Int>(ByteArray(16 * 1024 * 1024))
            }
            assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
            assertTrue(ex.suppressed.isEmpty(), "expected no failed write behind it, got ${ex.suppressed.toList()}")
        }
    }

    @Test
    fun `a COPY IN streamed past transaction_timeout reports the timeout, not the write it broke`() {
        assumeUnreadFatalSurvives()
        openSession().use { session ->
            val ex = assertFailsWith<SessionTerminatedException> {
                session.transaction.required(transactionTimeout = 200.milliseconds) {
                    copy.copyIn("COPY census FROM STDIN", slowRows(forMillis = 5_000))
                }
            }
            logger.error(ex) { "" }
            assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, ex.reason)
        }
    }

    /** One row every twenty milliseconds, each read on its own, until [forMillis] have passed. */
    private fun slowRows(forMillis: Long): InputStream = object : InputStream() {
        private val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(forMillis)
        private var id = 0

        override fun read(): Int = throw UnsupportedOperationException("read in chunks")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (System.nanoTime() > until) return -1
            Thread.sleep(20)
            val row = "${id++}\tcensor\n".toByteArray()
            row.copyInto(b, off)
            return row.size
        }
    }
}
