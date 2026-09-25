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

    @Test
    fun `should throw StatementException with correct position for native query syntax error`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // Error at 'FRO', which is at index 9, position 10
                session.createNativeQuery("SELECT * FRO test_table").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.SYNTAX_ERROR, exception.reason)
            assertEquals(10, exception.position)

            val ctx = exception.queryContext
            assertNotNull(ctx)
            assertEquals("SELECT * FRO test_table", ctx.sql)
            // For native query, dbSql is the same as sql
            assertEquals("SELECT * FRO test_table", ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException with correct position for named query syntax error`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // Named parameter query transforms "SELECT @param FRO test_table"
                // into "SELECT $1 FRO test_table"
                // The position from DB will be for the transformed query ("SELECT $1 FRO test_table").
                session.createNamedQuery("SELECT @param FRO test_table")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.SYNTAX_ERROR, exception.reason)
            // The error is actually at "test_table" (position 15), because PostgreSQL treats "FRO" as a column alias for $1.
            assertEquals(15, exception.position)

            val ctx = exception.queryContext
            assertNotNull(ctx)
            assertEquals("SELECT @param FRO test_table", ctx.sql)
            assertEquals("SELECT $1 FRO test_table", ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for unclosed quote in parser for named query`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // The unclosed quote starts at index 15
                session.createNamedQuery("SELECT @param, 'unclosed quote test")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, exception.reason)
            // position is 1-indexed, so 15 + 1 = 16
            assertEquals(16, exception.position)

            val ctx = assertNotNull(exception.queryContext)
            assertEquals("SELECT @param, 'unclosed quote test", ctx.sql)
            assertNull(ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for undefined object with correct position`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                session.createNativeQuery("SELECT * FROM some_non_existent_table").fetchRows()
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNDEFINED_OBJECT, exception.reason)
            // Position points to 'some_non_existent_table'. "SELECT * FROM " is 14 chars.
            // 's' is at position 15.
            assertEquals(15, exception.position)
        }
    }

    @Test
    fun `should throw StatementException for unclosed comment in parser for named query`() {
        openSession().use { session ->

            val exception = assertFailsWith<StatementException> {
                // The unclosed comment starts at index 14
                session.createNamedQuery("SELECT @param /* unclosed comment")
                    .fetchRow("param" to 1)
            }
            logger.error(exception) { "" }
            assertEquals(StatementExceptionReason.UNCLOSED_TOKEN, exception.reason)
            // position is 1-indexed, so 14 + 1 = 15
            assertEquals(15, exception.position)
            val ctx = assertNotNull(exception.queryContext)
            assertEquals("SELECT @param /* unclosed comment", ctx.sql)
            assertNull(ctx.dbSql)
        }
    }

    @Test
    fun `should throw StatementException for duplicate object`() {
        openSession().use { session ->

            session.createNativeQuery("CREATE TABLE IF NOT EXISTS duplicate_table_test (id INT)").execute()

            try {
                val exception = assertFailsWith<StatementException> {
                    session.createNativeQuery("CREATE TABLE duplicate_table_test (id INT)").execute()
                }
                assertEquals(StatementExceptionReason.DUPLICATE_OBJECT, exception.reason)
            } finally {
                session.createNativeQuery("DROP TABLE IF EXISTS duplicate_table_test").execute()
            }
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
