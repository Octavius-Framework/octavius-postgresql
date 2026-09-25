package io.github.octaviusframework.driver.spring

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.driver.spring.exception.OctaviusExceptionTranslator
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.net.ServerSocket
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * Covers the rule that an Octavius failure keeps its type on the way through Spring, wherever in the
 * cause chain it sits - including a connection that was never obtained in the first place.
 */
class OctaviusExceptionTranslationTest {

    private val translator = OctaviusExceptionTranslator()

    /** A port nothing is listening on, so connecting to it is refused rather than hanging. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `should translate a failure to obtain a connection`() {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:octavius://${TestDatabase.HOST}:${closedPort()}/${TestDatabase.DATABASE}"
            username = TestDatabase.USER
            password = TestDatabase.PASSWORD
            driverClassName = "io.github.octaviusframework.driver.jdbc.OctaviusDriver"
            connectionTimeout = 500
            initializationFailTimeout = -1 // do not probe the database while building the pool
        })

        dataSource.use {
            val ex = assertThrows(OctaviusDataAccessException::class.java) {
                OctaviusTemplate(it).execute { createNativeQuery("SELECT 1").execute() }
            }

            val octavius = assertInstanceOf<InitializationException>(ex.octaviusException)
            assertEquals(InitializationExceptionReason.CONNECTION_ERROR, octavius.reason)
        }
    }

    @Test
    fun `should translate a SQLExceptionWrapper`() {
        val wrapped = StatementException(StatementExceptionReason.SYNTAX_ERROR, sqlState = "42601")

        val translated = translator.translate("task", null, SQLExceptionWrapper(wrapped))

        assertSame(wrapped, assertInstanceOf<OctaviusDataAccessException>(translated).octaviusException)
    }

    @Test
    fun `should translate a SQLExceptionWrapper nested in the cause chain`() {
        val wrapped = StatementException(StatementExceptionReason.SYNTAX_ERROR, sqlState = "42601")
        val ex = SQLException("pool wrapped it", SQLExceptionWrapper(wrapped))

        val translated = translator.translate("task", null, ex)

        assertSame(wrapped, assertInstanceOf<OctaviusDataAccessException>(translated).octaviusException)
    }

    @Test
    fun `should translate a bare OctaviusException nested in the cause chain`() {
        // The shape HikariCP produces: its own SQLException over the driver's unwrapped exception.
        val octavius = InitializationException(InitializationExceptionReason.CONNECTION_ERROR)
        val ex = SQLTransientConnectionException("Connection is not available", octavius)

        val translated = translator.translate("task", null, ex)

        assertSame(octavius, assertInstanceOf<OctaviusDataAccessException>(translated).octaviusException)
    }

    @Test
    fun `should fall back to Spring for exceptions with nothing Octavius in them`() {
        val translated = translator.translate("task", null, SQLException("foreign", "23505"))

        assertNotNull(translated)
        assertFalse(translated is OctaviusDataAccessException, "expected Spring's own translation, got $translated")
    }
}
