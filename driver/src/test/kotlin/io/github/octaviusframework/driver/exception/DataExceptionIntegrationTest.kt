package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataExceptionIntegrationTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val schema = "CREATE TABLE legion_numerals (numeral VARCHAR(3))"

    @Test
    fun `should throw DIVISION_BY_ZERO`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT 3000 / 0 AS spoils_per_legion").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.DIVISION_BY_ZERO, exception.reason)
        }
    }

    @Test
    fun `should throw INVALID_FORMAT`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT 'XLII'::int").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.INVALID_FORMAT, exception.reason)
        }
    }

    @Test
    fun `should throw NUMERIC_OUT_OF_RANGE`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT 10000000000::int").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.NUMERIC_OUT_OF_RANGE, exception.reason)
        }
    }
    
    @Test
    fun `should throw DATA_TRUNCATION`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("INSERT INTO legion_numerals VALUES ('XVIII')").execute()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.DATA_TRUNCATION, exception.reason)
        }
    }

    @Test
    fun `should throw ARRAY_SUBSCRIPT_ERROR`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT ARRAY[ARRAY[1,2], ARRAY[1]]").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.ARRAY_SUBSCRIPT_ERROR, exception.reason)
        }
    }

    @Test
    fun `should throw JSON_ERROR`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT '{\"legio\": \"X Equestris\"'::json").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.INVALID_FORMAT, exception.reason)
        }
    }

    @Test
    fun `should throw REGEX_ERROR`() {
        openSession().use { session ->
            val exception = assertFailsWith<DataException> {
                session.createNativeQuery("SELECT 'Caesar' ~ '*Caesar'").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(DataExceptionReason.REGEX_ERROR, exception.reason)
        }
    }
}
