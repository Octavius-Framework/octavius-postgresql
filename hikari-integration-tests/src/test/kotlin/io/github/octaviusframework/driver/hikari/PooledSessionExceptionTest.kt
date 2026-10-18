package io.github.octaviusframework.driver.hikari

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.SessionTerminatedExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase
import io.github.octaviusframework.testsupport.assumeUnreadFatalSurvives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.TimeUnit

/**
 * A pool reports its own failures as `java.sql.SQLException` - a borrow that timed out, a pool that
 * has been closed, credentials it declines to forward - and so does its proxy once the connection
 * underneath it is gone. None of those is a shape the session API uses anywhere else, so all of
 * them are restated as the driver's own on the way out.
 */
class PooledSessionExceptionTest {

    private fun pool(configure: HikariConfig.() -> Unit = {}) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = TestDatabase.URL
        username = TestDatabase.USER
        password = TestDatabase.PASSWORD
        maximumPoolSize = 1
        configure()
    })

    /** A port nothing is listening on, so connecting to it is refused rather than hanging. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `should report a borrow that timed out as a connection the pool did not have`() {
        pool { connectionTimeout = 500 }.use { ds ->
            ds.getOctaviusSession().use {
                val ex = assertThrows<InitializationException> { ds.getOctaviusSession() }

                // Having none to give is not failing to open one: nothing reached the server here, and
                // what ran out was this application's own supply. The closed-pool case below is the other
                // side of that line - a pool that is gone rather than busy.
                assertEquals(InitializationExceptionReason.CONNECTION_UNAVAILABLE, ex.reason)
                assertInstanceOf<SQLTransientConnectionException>(ex.cause)
                assertTrue(
                    ex.details!!.contains("Connection is not available"),
                    "the pool's own account of it has to survive the restatement: ${ex.details}"
                )
            }
        }
    }

    @Test
    fun `should report a closed pool as an Octavius failure`() {
        val ds = pool()
        ds.close()

        val ex = assertThrows<InitializationException> { ds.getOctaviusSession() }

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertInstanceOf<SQLException>(ex.cause)
    }

    @Test
    fun `should report per-call credentials the pool refuses as an Octavius failure`() {
        pool().use { ds ->
            val ex = assertThrows<InitializationException> { ds.getOctaviusSession(TestDatabase.USER, TestDatabase.PASSWORD) }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
            assertInstanceOf<SQLFeatureNotSupportedException>(ex.cause)
        }
    }

    @Test
    fun `should keep the driver's own exception when the pool could not reach the server`() {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            connectionTimeout = 500
            initializationFailTimeout = -1 // do not probe the database while building the pool
        })

        ds.use {
            val ex = assertThrows<InitializationException> { it.getOctaviusSession() }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
            // The one the driver raised on the refused connect, not one restated over the pool's
            // timeout - so what it says is why the server could not be reached.
            assertFalse(ex.cause is SQLException, "expected the driver's own exception, got ${ex.cause}")
        }
    }

    /** Waits until the backend has left `pg_stat_activity` - by then it has sent its FATAL. */
    private fun awaitGone(pid: Int) {
        TestDatabase.openSession().use { observer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (observer.createNativeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = $1").fetchFieldStrict<Long>(pid) > 0) {
                check(System.nanoTime() < deadline) { "backend $pid never went away" }
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `should give up a connection whose session the server ended, and hand out a working one`() {
        assumeUnreadFatalSurvives()
        pool().use { ds ->
            val pid = ds.getOctaviusSession().use { session ->
                val pid = session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()
                TestDatabase.openSession().use {
                    it.createNativeQuery("SELECT pg_terminate_backend($1)").fetchFieldStrict<Boolean>(pid)
                }
                awaitGone(pid)

                val ex = assertThrows<SessionTerminatedException> { session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
                assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
                pid
            }

            // Closing the session gave the connection up rather than back, so the pool opened another
            ds.getOctaviusSession().use { session ->
                assertNotEquals(pid, session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>())
            }
        }
    }

    @Test
    fun `should give up a connection whose stream broke rather than keep it borrowed`() {
        pool {
            jdbcUrl = "${TestDatabase.URL}?socketTimeout=1"
            connectionTimeout = 5_000
        }.use { ds ->
            ds.getOctaviusSession().use { session ->
                assertThrows<NetworkException> { session.createNativeQuery("SELECT pg_sleep(2)").fetchRows() }
            }

            // A broken stream latches the connection closed, and the abort that closing the session sends has
            // to raise all the same - it is the pool's only signal to evict. Swallowed, the pool of one would
            // count the connection borrowed for good and this borrow would time out.
            ds.getOctaviusSession().use { session ->
                assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
            }
        }
    }

    @Test
    fun `should have the pool evict a connection the server ended, whatever code it ended it with`() {
        assumeUnreadFatalSurvives()
        pool().use { ds ->
            ds.connection.use { connection ->
                connection.createStatement().use { it.execute("SET idle_session_timeout = '100ms'") }
                val pid = connection.getOctaviusSession(ownsConnection = false).use {
                    it.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()
                }
                awaitGone(pid)

                // 57P05 is not among the codes HikariCP reads as a lost connection; the 08006 chained behind
                // it is, so the pool learns now rather than at its next validation. A session setting, because
                // it goes to the server through the pool's own proxy, where the pool can see what it raises.
                val ex = assertThrows<SQLException> { connection.isReadOnly = true }
                assertEquals("57P05", ex.sqlState)
                assertEquals("08006", ex.nextException?.sqlState)
                assertTrue(connection.isClosed, "the pool's proxy should have evicted the connection")
            }
        }
    }

    @Test
    fun `should keep the driver's own exception when the pool starts on the first borrow and cannot`() {
        // Made with the no-argument constructor, as Spring Boot makes it: the pool starts on the first borrow,
        // and the driver's exception there - unchecked - leaves Hikari as its own PoolInitializationException.
        HikariDataSource().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            connectionTimeout = 500
        }.use {
            val ex = assertThrows<InitializationException> { it.getOctaviusSession() }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        }
    }

    @Test
    fun `should report use after close as an Octavius failure`() {
        pool().use { ds ->
            val session = ds.getOctaviusSession()
            session.close()

            // The connection is the pool's again, and its proxy answers everything from here with a
            // bare java.sql.SQLException - which is not a type this API declares anywhere.
            val ex = assertThrows<NetworkException> { session.autoCommit }

            assertEquals(NetworkExceptionReason.CONNECTION_CLOSED, ex.reason)
        }
    }
}
