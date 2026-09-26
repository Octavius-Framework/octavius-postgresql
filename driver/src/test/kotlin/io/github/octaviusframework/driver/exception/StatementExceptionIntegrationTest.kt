package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StatementExceptionIntegrationTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val schema = "CREATE TABLE curia (id INT)"

    @Test
    fun `should throw StatementException with correct position for native query syntax error`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // Error at 'FRO', which is at index 9, position 10
                session.createNativeQuery("SELECT * FRO senators").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.SYNTAX_ERROR, exception.reason)
            assertEquals(10, exception.position)

            val ctx = exception.queryContext
            assertNotNull(ctx)
            assertEquals("SELECT * FRO senators", ctx.sql)
            // For native query, dbSql is the same as sql
            assertEquals("SELECT * FRO senators", ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException with correct position for named query syntax error`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // Named parameter query transforms "SELECT @param FRO senators"
                // into "SELECT $1 FRO senators"
                // The position from DB will be for the transformed query ("SELECT $1 FRO senators").
                session.createNamedQuery("SELECT @param FRO senators")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.SYNTAX_ERROR, exception.reason)
            // The error is actually at "senators" (position 15), because PostgreSQL treats "FRO" as a column alias for $1.
            assertEquals(15, exception.position)

            val ctx = exception.queryContext
            assertNotNull(ctx)
            assertEquals("SELECT @param FRO senators", ctx.sql)
            assertEquals("SELECT $1 FRO senators", ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for unclosed quote in parser for named query`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // The unclosed quote starts at index 15
                session.createNamedQuery("SELECT @param, 'Carthago delenda est")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, exception.reason)
            // position is 1-indexed, so 15 + 1 = 16
            assertEquals(16, exception.position)

            val ctx = assertNotNull(exception.queryContext)
            assertEquals("SELECT @param, 'Carthago delenda est", ctx.sql)
            assertNull(ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for undefined object with correct position`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                session.createNativeQuery("SELECT * FROM atlantis").fetchRows()
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNDEFINED_OBJECT, exception.reason)
            // Position points to 'atlantis'. "SELECT * FROM " is 14 chars.
            // 'a' is at position 15.
            assertEquals(15, exception.position)
        }
    }

    @Test
    fun `should throw StatementException for unclosed comment in parser for named query`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // The unclosed comment starts at index 14
                session.createNamedQuery("SELECT @param /* the gates of Janus stand open")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, exception.reason)
            // position is 1-indexed, so 14 + 1 = 15
            assertEquals(15, exception.position)
            val ctx = assertNotNull(exception.queryContext)
            assertEquals("SELECT @param /* the gates of Janus stand open", ctx.sql)
            assertNull(ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for duplicate object`() {
        openSession().use { session ->

            // One senate house already stands
            val exception = assertFailsWith<StatementException> {
                session.createNativeQuery("CREATE TABLE curia (id INT)").execute()
            }
            assertEquals(StatementExceptionReason.DUPLICATE_OBJECT, exception.reason)
        }
    }

    @Test
    fun `should throw StatementException for data type error`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // Trigger 42804 datatype_mismatch
                session.createNativeQuery("SELECT 1 UNION SELECT current_date").fetchRows()
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.DATA_TYPE_ERROR, exception.reason)
        }
    }
}
