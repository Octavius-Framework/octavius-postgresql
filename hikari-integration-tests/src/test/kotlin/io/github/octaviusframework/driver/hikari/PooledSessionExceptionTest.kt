package io.github.octaviusframework.driver.hikari

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLTransientConnectionException

/**
 * A pool reports its own failures as `java.sql.SQLException` - a borrow that timed out, a pool that
 * has been closed, credentials it declines to forward - and so does its proxy once the connection
 * underneath it is gone. None of those is a shape the session API uses anywhere else, so all of
 * them are restated as the driver's own on the way out.
 */
class PooledSessionExceptionTest {

    private fun pool(configure: HikariConfig.() -> Unit = {}) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:octavius://localhost:5432/octavius_test"
        username = "postgres"
        password = "1234"
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
            val ex = assertThrows<InitializationException> { ds.getOctaviusSession("postgres", "1234") }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
            assertInstanceOf<SQLFeatureNotSupportedException>(ex.cause)
        }
    }

    @Test
    fun `should keep the driver's own exception when the pool could not reach the server`() {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:octavius://localhost:${closedPort()}/octavius_test"
            username = "postgres"
            password = "1234"
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
