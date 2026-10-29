package io.github.octaviusframework.driver.copy

import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.ConstraintViolationExceptionReason
import io.github.octaviusframework.driver.exception.DataException
import io.github.octaviusframework.driver.exception.DataExceptionReason
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.fail

class CopyManagerTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    override val schema = """
        CREATE TABLE census (id INT, name TEXT);
        CREATE TABLE legio (id INT PRIMARY KEY);
        CREATE TABLE miles (id INT, legio_id INT REFERENCES legio);
    """.trimIndent()

    @BeforeEach
    fun setup() {
        session = openSession()
        session.createNativeQuery("TRUNCATE TABLE census").execute()
    }

    @AfterEach
    fun teardown() {
        if (::session.isInitialized) {
            session.close()
        }
    }

    @Test
    fun testCopyInAndCopyOutWithStreams() {
        val copyManager = session.copy

        // 1. COPY IN
        val inputData = "1,Marcus\n2,Gaius\n3,Lucius\n"
        val inputStream = ByteArrayInputStream(inputData.toByteArray(Charsets.UTF_8))
        
        val rowsAffected = copyManager.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)", inputStream)
        assertEquals(3, rowsAffected)

        // Verify data in the database
        val count = session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>()
        assertEquals(3L, count)

        // 2. COPY OUT
        val outputStream = ByteArrayOutputStream()
        copyManager.copyOut("COPY census TO STDOUT WITH (FORMAT CSV)", outputStream)
        
        val outputData = outputStream.toString(Charsets.UTF_8.name())
        assertEquals("1,Marcus\n2,Gaius\n3,Lucius\n", outputData)
    }

    @Test
    fun testCopyInAndCopyOutManualChunks() {
        val copyManager = session.copy

        // 1. COPY IN manually
        val copyIn = copyManager.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("4,Publius\n".toByteArray(Charsets.UTF_8))
        copyIn.writeToCopy("5,Quintus\n".toByteArray(Charsets.UTF_8))
        val rowsAffected = copyIn.endCopy()
        assertEquals(2, rowsAffected)

        // 2. COPY OUT manually
        val copyOut = copyManager.copyOut("COPY census TO STDOUT WITH (FORMAT CSV)")
        val resultBytes = ByteArrayOutputStream()
        while (true) {
            val chunk = copyOut.readFromCopy() ?: break
            resultBytes.write(chunk)
        }
        
        val outputData = resultBytes.toString(Charsets.UTF_8.name())
        assertEquals("4,Publius\n5,Quintus\n", outputData)
    }

    @Test
    fun testQueriesAreRejectedWhileCopyIsInProgress() {
        val copyIn = session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("6,Titus\n".toByteArray(Charsets.UTF_8))

        val error = assertFailsWith<InvalidOperationException> {
            session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, error.reason)
        // The reason is shared with reentrant execution, so the details are what say which branch fired.
        assertTrue(error.details!!.contains("COPY operation is still in progress"))

        // The rejection must not disturb the transfer itself
        assertEquals(1, copyIn.endCopy())
        assertEquals(1L, session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
    }

    @Test
    fun testSecondCopyOnTheSameSessionIsRejected() {
        val copyIn = session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        try {
            val error = assertFailsWith<InvalidOperationException> {
                session.copy.copyOut("COPY census TO STDOUT WITH (FORMAT CSV)")
            }
            assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, error.reason)
            assertTrue(error.details!!.contains("COPY operation is still in progress"))
        } finally {
            copyIn.cancelCopy()
        }
    }

    @Test
    fun testNonPositiveBufferSizeIsRejectedBeforeTheCopyStarts() {
        val error = assertFailsWith<InvalidOperationException> {
            session.copy.copyIn(
                "COPY census FROM STDIN WITH (FORMAT CSV)",
                ByteArrayInputStream("8,Aulus\n".toByteArray(Charsets.UTF_8)),
                0
            )
        }
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, error.reason)

        // Rejected before the statement went out, so the session is untouched
        assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
    }

    @Test
    fun testClosingSessionWithAnUnfinishedCopyDropsTheConnection() {
        val copyIn = session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("7,Gnaeus\n".toByteArray(Charsets.UTF_8))

        // No endCopy(). The transfer is not ended on the caller's behalf - the connection
        // carrying it goes instead, so nothing of the copy is committed either way.
        session.close()
        assertFalse(session.isValid(1), "a connection in copy mode must not survive the close")

        session = openSession()
        val count = session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>()
        assertEquals(0L, count)
    }

    @Test
    fun testACopyCanBeWhatBeginsATransaction() {
        // Nothing has run since auto-commit went off, so the BEGIN is still waiting - and a COPY starts on the
        // stream rather than through an ordinary statement. It has to send that BEGIN itself, or the rows would
        // land in a transaction of their own and survive the rollback.
        session.autoCommit = false
        session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)", "1,Primus\n".byteInputStream())

        session.rollback()
        session.autoCommit = true
        assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
    }

    @Test
    fun testCancellingACopyInATransactionLeavesTheTransactionStateFailed() {
        session.autoCommit = false
        session.createNativeQuery("INSERT INTO census VALUES (9, 'Sextus')").executeUpdate()
        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)

        val copyIn = session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("10,Decimus\n".toByteArray(Charsets.UTF_8))
        copyIn.cancelCopy()

        // Cancelling is done with an error, and an error inside a transaction block fails it. The
        // state has to say so: a COMMIT from here rolls the transaction back and reports success,
        // so a session that still believed it was IN_TRANSACTION would be believing the wrong thing
        // at exactly the moment it matters.
        assertEquals(TransactionState.FAILED, session.transactionState)

        session.rollback()
        session.autoCommit = true
        assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
    }

    @Test
    fun testARowTheServerRejectsIsReportedByTheNextWrite() {
        // A session of its own with a read timeout, so a driver that waits for a reply the server has already
        // sent fails here rather than hanging the suite.
        openSession { socketTimeout = 5 }.use { copying ->
            val pid = copying.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()
            val copyIn = copying.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
            copyIn.writeToCopy("x,Marcus\n".toByteArray(Charsets.UTF_8))

            // The server rejects the row as soon as it reads it, answers with an ERROR and a ReadyForQuery, and
            // drops whatever copy data comes after - so its backend is idle again while the handle is still open.
            awaitIdle(pid)

            val error = assertFailsWith<DataException> {
                repeat(3) { copyIn.writeToCopy("${it + 1},Gaius\n".toByteArray(Charsets.UTF_8)) }
            }
            assertEquals(DataExceptionReason.INVALID_FORMAT, error.reason)
            assertEquals("22P02", error.sqlState)
            assertFalse(copyIn.isActive, "the copy ended with the rejection")

            // Out of copy mode and in step with the server
            assertEquals(TransactionState.IDLE, copying.transactionState)
            assertEquals(0L, copying.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
        }
    }

    @Test
    fun testTheStreamOverloadStopsReadingOnceTheServerRejectsARow() {
        openSession { socketTimeout = 5 }.use { copying ->
            val pid = copying.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()
            var chunksRead = 0
            // One row per chunk, the first one bad, and a thousand more after it. Each of those waits for the server
            // to have rejected the first, so the rejection is there to be seen before another chunk goes out.
            val input = object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException("read in chunks")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (chunksRead > 1000) return -1
                    chunksRead++
                    if (chunksRead > 1) awaitIdle(pid)
                    val row = (if (chunksRead == 1) "x,Marcus\n" else "$chunksRead,Gaius\n").toByteArray(Charsets.UTF_8)
                    row.copyInto(b, off)
                    return row.size
                }
            }

            assertFailsWith<DataException> {
                copying.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)", input)
            }
            assertTrue(chunksRead <= 3, "expected the input left alone after the rejection, but $chunksRead chunks were read")
            assertEquals(TransactionState.IDLE, copying.transactionState)
        }
    }

    @Test
    fun testAForeignKeyIsCheckedOnlyByEndCopy() {
        val pid = session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()
        val copyIn = session.copy.copyIn("COPY miles FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("1,99\n".toByteArray(Charsets.UTF_8))

        // Ample time for a rejection to arrive, were there one: the server checks foreign keys when the statement
        // ends, so it is still in the copy, and the next chunk goes through
        Thread.sleep(200)
        openSession().use { observer ->
            assertEquals("active", observer.createNativeQuery("SELECT state FROM pg_stat_activity WHERE pid = $1").fetchField<String?>(pid))
        }
        copyIn.writeToCopy("2,99\n".toByteArray(Charsets.UTF_8))

        val error = assertFailsWith<ConstraintViolationException> { copyIn.endCopy() }
        assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, error.reason)
    }

    /** Polls `pg_stat_activity` from [session] until [pid]'s backend is idle. */
    private fun awaitIdle(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (session.createNativeQuery("SELECT state FROM pg_stat_activity WHERE pid = $1").fetchField<String?>(pid) != "idle") {
            if (System.nanoTime() > deadline) fail("backend $pid never went idle")
            Thread.sleep(20)
        }
    }

    @Test
    fun testFinishingACopyInATransactionLeavesTheTransactionStateIntact() {
        session.autoCommit = false
        val copyIn = session.copy.copyIn("COPY census FROM STDIN WITH (FORMAT CSV)")
        copyIn.writeToCopy("11,Servius\n".toByteArray(Charsets.UTF_8))
        assertEquals(1, copyIn.endCopy())

        assertEquals(TransactionState.IN_TRANSACTION, session.transactionState)

        session.rollback()
        session.autoCommit = true
        assertEquals(0L, session.createNativeQuery("SELECT count(*) FROM census").fetchFieldStrict<Long>())
    }
}
