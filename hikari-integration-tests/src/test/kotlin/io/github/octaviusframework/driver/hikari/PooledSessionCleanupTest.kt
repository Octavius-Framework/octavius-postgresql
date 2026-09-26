package io.github.octaviusframework.driver.hikari

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A pooled connection outlives the session borrowed through it, so whatever that session left
 * on the connection would otherwise become the next borrower's starting state. Closing a session
 * has to hand back a connection that looks untouched.
 */
class PooledSessionCleanupTest {

    private fun pool() = HikariDataSource(HikariConfig().apply {
        jdbcUrl = TestDatabase.URL
        username = TestDatabase.USER
        password = TestDatabase.PASSWORD
        maximumPoolSize = 1   // guarantees the same physical connection comes back
        minimumIdle = 1
    })

    private fun OctaviusSession.listeningChannels(): Long =
        createNativeQuery("SELECT count(*) FROM pg_listening_channels()").fetchFieldStrict()

    private fun OctaviusSession.backendPid(): Int =
        createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict()

    // ---------------------------------------------------------------- LISTEN registrations

    @Test
    fun `a returned connection carries no subscriptions from its previous borrower`() {
        pool().use { pool ->
            val first = pool.getOctaviusSession()
            val pid = first.backendPid()
            first.notifications.listen("cleanup_chan", "cleanup_chan_2")
            assertEquals(2L, first.listeningChannels(), "the subscriptions should be live while in use")
            first.close()

            val second = pool.getOctaviusSession()
            assertEquals(pid, second.backendPid(), "expected the same physical connection back")
            assertEquals(0L, second.listeningChannels(), "the previous borrower's subscriptions leaked")
            second.close()
        }
    }

    @Test
    fun `a session that never subscribed still closes cleanly`() {
        pool().use { pool ->
            val session = pool.getOctaviusSession()
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
            session.close()

            val next = pool.getOctaviusSession()
            assertEquals(0L, next.listeningChannels())
            next.close()
        }
    }

    @Test
    fun `unlistening by hand before closing is not undone twice`() {
        pool().use { pool ->
            val session = pool.getOctaviusSession()
            session.notifications.listen("cleanup_chan_3")
            session.notifications.unlistenAll()
            assertEquals(0L, session.listeningChannels())
            session.close()

            val next = pool.getOctaviusSession()
            assertEquals(1, next.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
            assertEquals(0L, next.listeningChannels())
            next.close()
        }
    }

    // ------------------------------------------------------------------ a COPY left in flight

    /**
     * The transfer is not ended on the caller's behalf: `CopyOut.cancelCopy()` would have to read
     * the rest of the export first, on whatever thread handed the session back. The connection
     * leaves the pool instead, and what the copy had written never lands.
     */
    @Test
    fun `a session closed with a COPY still open loses its connection instead of returning it`() {
        pool().use { pool ->
            val first = pool.getOctaviusSession()
            first.createNativeQuery("CREATE TABLE IF NOT EXISTS pool_cleanup_copy (id INT)").execute()
            first.createNativeQuery("TRUNCATE pool_cleanup_copy").execute()
            val pid = first.backendPid()

            val copyIn = first.copy.copyIn("COPY pool_cleanup_copy FROM STDIN WITH (FORMAT CSV)")
            copyIn.writeToCopy("1\n".toByteArray())
            first.close() // neither endCopy() nor cancelCopy()

            val second = pool.getOctaviusSession()
            assertNotEquals(pid, second.backendPid(), "a connection in copy mode was handed to the next borrower")
            assertEquals(
                0L,
                second.createNativeQuery("SELECT count(*) FROM pool_cleanup_copy").fetchFieldStrict<Long>(),
                "a COPY IN that never reached endCopy() must land nothing"
            )
            second.createNativeQuery("DROP TABLE pool_cleanup_copy").execute()
            second.close()
        }
    }

    // ------------------------------------------------------- transactions the driver never opened

    @Test
    fun `a hand-written BEGIN does not follow the connection to the next borrower`() {
        val observer = TestDatabase.openSession()
        observer.createNativeQuery("CREATE TABLE IF NOT EXISTS pool_cleanup_test (id INT)").execute()
        observer.createNativeQuery("TRUNCATE pool_cleanup_test").execute()

        pool().use { pool ->
            val first = pool.getOctaviusSession()
            first.createNativeQuery("BEGIN").execute() // the driver is never told
            first.createNativeQuery("INSERT INTO pool_cleanup_test VALUES (1)").update()
            assertEquals(TransactionState.IN_TRANSACTION, first.transactionState)
            first.close()

            val second = pool.getOctaviusSession()
            assertEquals(TransactionState.IDLE, second.transactionState, "an open transaction leaked into the pool")
            second.close()
        }

        assertEquals(
            0L,
            observer.createNativeQuery("SELECT count(*) FROM pool_cleanup_test").fetchFieldStrict<Long>(),
            "the abandoned work should have been rolled back, not committed"
        )

        observer.createNativeQuery("DROP TABLE IF EXISTS pool_cleanup_test").execute()
        observer.close()
    }

    @Test
    fun `a properly committed manual transaction is left alone`() {
        val observer = TestDatabase.openSession()
        observer.createNativeQuery("CREATE TABLE IF NOT EXISTS pool_cleanup_test_2 (id INT)").execute()
        observer.createNativeQuery("TRUNCATE pool_cleanup_test_2").execute()

        pool().use { pool ->
            val session = pool.getOctaviusSession()
            session.autoCommit = false
            session.createNativeQuery("INSERT INTO pool_cleanup_test_2 VALUES (1)").update()
            session.commit()
            session.autoCommit = true
            session.close()
        }

        assertEquals(
            1L,
            observer.createNativeQuery("SELECT count(*) FROM pool_cleanup_test_2").fetchFieldStrict<Long>(),
            "committed work must survive the cleanup"
        )

        observer.createNativeQuery("DROP TABLE IF EXISTS pool_cleanup_test_2").execute()
        observer.close()
    }
}
