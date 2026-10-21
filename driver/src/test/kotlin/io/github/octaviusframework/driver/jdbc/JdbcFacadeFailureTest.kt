package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * How the JDBC surface fails: what it implements in a `SQLExceptionWrapper`, which a pool and a script runner read as
 * a `SQLException`, and what it does not implement as the driver's own exception.
 */
class JdbcFacadeFailureTest : AbstractIntegrationTest() {

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.URL, TestDatabase.USER, TestDatabase.PASSWORD).use(block)

    @Test
    fun `a statement the server rejects arrives as a SQLExceptionWrapper over the driver's exception`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> { connection.createStatement().execute("SELEC 1") }
            val cause = assertIs<StatementException>(wrapper.wrappedException)
            assertEquals(StatementExceptionReason.SYNTAX_ERROR, cause.reason)
            assertEquals("42601", wrapper.sqlState)
        }
    }

    @Test
    fun `executeUpdate fails the same way`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> {
                connection.createStatement().executeUpdate("UPDATE no_such_table SET x = 1")
            }
            assertIs<StatementException>(wrapper.wrappedException)
            assertEquals("42P01", wrapper.sqlState)
        }
    }

    @Test
    fun `a statement that returns rows is refused inside the wrapper`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> { connection.createStatement().execute("SELECT 1") }
            val cause = assertIs<InvalidOperationException>(wrapper.wrappedException)
            assertEquals(InvalidOperationExceptionReason.UNEXPECTED_RESULT, cause.reason)
        }
    }

    @Test
    fun `the connection stays usable after a failed statement`() {
        withConnection { connection ->
            val statement = connection.createStatement()
            assertFailsWith<SQLExceptionWrapper> { statement.execute("SELEC 1") }
            assertEquals(false, statement.execute("SET application_name = 'curia'"))
        }
    }

    @Test
    fun `unwrapping to a type it is not arrives as a SQLExceptionWrapper`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> { connection.unwrap(String::class.java) }
            assertEquals(InvalidOperationExceptionReason.UNWRAP_ERROR, assertIs<InvalidOperationException>(wrapper.wrappedException).reason)
        }
        val dataSource = OctaviusDataSource().apply { url = TestDatabase.URL }
        val wrapper = assertFailsWith<SQLExceptionWrapper> { dataSource.unwrap(String::class.java) }
        assertEquals(InvalidOperationExceptionReason.UNWRAP_ERROR, assertIs<InvalidOperationException>(wrapper.wrappedException).reason)
    }

    @Test
    fun `isValid with a negative timeout arrives as a SQLExceptionWrapper, and the session raises what it wraps`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> { connection.isValid(-1) }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, assertIs<InvalidOperationException>(wrapper.wrappedException).reason)
        }
        openSession().use { session ->
            val exception = assertFailsWith<InvalidOperationException> { session.isValid(-1) }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, exception.reason)
        }
    }

    @Test
    fun `abort without an executor arrives as a SQLExceptionWrapper and leaves the connection open`() {
        withConnection { connection ->
            val wrapper = assertFailsWith<SQLExceptionWrapper> { connection.abort(null) }
            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, assertIs<InvalidOperationException>(wrapper.wrappedException).reason)
            assertEquals(false, connection.isClosed)
        }
    }

    @Test
    fun `what the Statement does not implement throws the driver's exception unwrapped`() {
        withConnection { connection ->
            val exception = assertFailsWith<InvalidOperationException> { connection.createStatement().executeQuery("SELECT 1") }
            assertEquals(InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED, exception.reason)
        }
    }
}
