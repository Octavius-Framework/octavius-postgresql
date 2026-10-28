package io.github.octaviusframework.driver.spring

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.jdbc.datasource.AbstractDataSource
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/**
 * Covers the rule that a failure of the driver's leaves Spring as the driver's own exception, wherever
 * in the cause chain it sits - including a connection that was never obtained in the first place.
 */
class OctaviusSpringExceptionsTest {

    /** A port nothing is listening on, so connecting to it is refused rather than hanging. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /** A data source that hands over no connection, raising [failure] instead. */
    private fun raising(failure: Throwable) = object : AbstractDataSource() {
        override fun getConnection(): Connection = throw failure
        override fun getConnection(username: String, password: String): Connection = getConnection()
    }

    /** A pool that failed to start for a reason of its own, with nothing of the driver's in it. */
    private val poolThatWouldNotStart = raising(PoolInitializationException(SQLException("the pool's own reason")))

    /** [pool], with connections whose [method] - `commit` or `rollback` - is [call] rather than the connection's own. */
    private fun replacing(pool: DataSource, method: String, call: (Connection) -> Unit): DataSource =
        object : DelegatingDataSource(pool) {
            override fun getConnection(): Connection {
                val connection = super.getConnection()
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, invoked, args ->
                    if (invoked.name == method && args.isNullOrEmpty()) return@newProxyInstance call(connection)
                    try {
                        invoked.invoke(connection, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as Connection
            }
        }

    /**
     * [pool], with connections that go during the `COMMIT`: aborted first, so the pool evicts the connection and its
     * proxy answers the `COMMIT` itself with a bare `SQLException`. A connection that left between the manager asking
     * whether it was still there and the `COMMIT`.
     */
    private fun losingConnectionsOnCommit(pool: DataSource): DataSource = replacing(pool, "commit") { connection ->
        runCatching { connection.abort { it.run() } }
        connection.commit()
    }

    @Test
    fun `should report a failure to obtain a connection as the driver's exception`() {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            driverClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDriver"
            connectionTimeout = 500
            initializationFailTimeout = -1 // do not probe the database while building the pool
        })

        dataSource.use {
            val ex = assertThrows(InitializationException::class.java) {
                OctaviusTemplate(it).execute { createNativeQuery("SELECT 1").execute() }
            }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        }
    }

    @Test
    fun `should report a failure to start the pool on its first borrow as the driver's exception`() {
        // Made with the no-argument constructor, as Spring Boot makes it: the pool starts on the first
        // borrow, and the driver's exception there leaves Hikari as its own PoolInitializationException.
        HikariDataSource().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            driverClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDriver"
            connectionTimeout = 500
        }.use {
            val ex = assertThrows(InitializationException::class.java) {
                OctaviusTemplate(it).execute { createNativeQuery("SELECT 1").execute() }
            }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        }
    }

    @Test
    fun `should report a pool with none free as CONNECTION_UNAVAILABLE`() {
        TestDatabase.dataSource {
            maximumPoolSize = 1
            connectionTimeout = 250
        }.use { dataSource ->
            dataSource.connection.use {
                val ex = assertThrows(InitializationException::class.java) {
                    OctaviusTemplate(dataSource).execute { createNativeQuery("SELECT 1").execute() }
                }

                assertEquals(InitializationExceptionReason.CONNECTION_UNAVAILABLE, ex.reason)
                assertInstanceOf<SQLTransientConnectionException>(ex.cause)
            }
        }
    }

    @Test
    fun `should report a transaction whose pool could not start as the driver's exception`() {
        HikariDataSource().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            driverClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDriver"
            connectionTimeout = 500
        }.use {
            val transaction = TransactionTemplate(OctaviusJdbcTransactionManager(it))

            val ex = assertThrows(InitializationException::class.java) {
                transaction.executeWithoutResult { }
            }

            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        }
    }

    @Test
    fun `should report a transaction on a pool with none free as CONNECTION_UNAVAILABLE`() {
        TestDatabase.dataSource {
            maximumPoolSize = 1
            connectionTimeout = 250
        }.use { dataSource ->
            dataSource.connection.use {
                val transaction = TransactionTemplate(OctaviusJdbcTransactionManager(dataSource))

                val ex = assertThrows(InitializationException::class.java) {
                    transaction.executeWithoutResult { }
                }

                assertEquals(InitializationExceptionReason.CONNECTION_UNAVAILABLE, ex.reason)
            }
        }
    }

    @Test
    fun `should report a pool that would not start for a reason of its own as the driver's exception`() {
        val ex = assertThrows(InitializationException::class.java) {
            OctaviusTemplate(poolThatWouldNotStart).execute { createNativeQuery("SELECT 1").execute() }
        }

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertInstanceOf<PoolInitializationException>(ex.cause)
    }

    @Test
    fun `should report a transaction on a pool that would not start for a reason of its own as the driver's exception`() {
        val ex = assertThrows(InitializationException::class.java) {
            TransactionTemplate(OctaviusJdbcTransactionManager(poolThatWouldNotStart)).executeWithoutResult { }
        }

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertInstanceOf<PoolInitializationException>(ex.cause)
    }

    @Test
    fun `should report a commit whose connection went during the COMMIT as the connection gone`() {
        TestDatabase.dataSource().use { pool ->
            val dataSource = losingConnectionsOnCommit(pool)

            val ex = assertThrows(NetworkException::class.java) {
                TransactionTemplate(OctaviusJdbcTransactionManager(dataSource)).executeWithoutResult {
                    OctaviusTemplate(dataSource).execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
                }
            }

            assertEquals(NetworkExceptionReason.CONNECTION_ABORTED, ex.reason)
        }
    }

    @Test
    fun `should restate a commit the pool refused on a connection still there as the driver's exception`() {
        TestDatabase.dataSource().use { pool ->
            val refusal = SQLException("the pool's own reason")
            val dataSource = replacing(pool, "commit") { throw refusal }

            val ex = assertThrows(NetworkException::class.java) {
                TransactionTemplate(OctaviusJdbcTransactionManager(dataSource)).executeWithoutResult {
                    OctaviusTemplate(dataSource).execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
                }
            }

            assertEquals(NetworkExceptionReason.CONNECTION_ERROR, ex.reason)
            assertSame(refusal, ex.cause)
        }
    }

    @Test
    fun `should restate a rollback the pool refused on a connection still there as the driver's exception`() {
        TestDatabase.dataSource().use { pool ->
            val refusal = SQLException("the pool's own reason")
            val dataSource = replacing(pool, "rollback") { throw refusal }

            val ex = assertThrows(NetworkException::class.java) {
                TransactionTemplate(OctaviusJdbcTransactionManager(dataSource)).executeWithoutResult { status ->
                    OctaviusTemplate(dataSource).execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
                    status.setRollbackOnly()
                }
            }

            assertEquals(NetworkExceptionReason.CONNECTION_ERROR, ex.reason)
            assertSame(refusal, ex.cause)
        }
    }

    @Test
    fun `should let an Error taking the connection leave as it was thrown`() {
        val error = LinkageError("the pool's class would not load")

        val ex = assertThrows(LinkageError::class.java) {
            TransactionTemplate(OctaviusJdbcTransactionManager(raising(error))).executeWithoutResult { }
        }

        assertSame(error, ex)
    }
}
