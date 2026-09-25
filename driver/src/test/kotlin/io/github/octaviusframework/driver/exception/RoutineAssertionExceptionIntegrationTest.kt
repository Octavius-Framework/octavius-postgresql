package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutineAssertionExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `should throw NO_DATA_FOUND`() {
        openSession().use { session ->
            val exception = assertFailsWith<RoutineAssertionException> {
                session.createNativeQuery("""
                    DO $$
                    DECLARE
                        temp_var INT;
                    BEGIN
                        SELECT 1 INTO STRICT temp_var WHERE false;
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("P0002", exception.sqlState)
            assertEquals(RoutineAssertionExceptionReason.NO_DATA_FOUND, exception.reason)
            assertNotNull(exception.where)
            assertTrue(exception.where!!.isNotEmpty())
        }
    }

    @Test
    fun `should throw TOO_MANY_ROWS`() {
        openSession().use { session ->
            val exception = assertFailsWith<RoutineAssertionException> {
                session.createNativeQuery("""
                    DO $$
                    DECLARE
                        temp_var INT;
                    BEGIN
                        SELECT * INTO STRICT temp_var FROM (VALUES (1), (2)) AS t(c);
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("P0003", exception.sqlState)
            assertEquals(RoutineAssertionExceptionReason.TOO_MANY_ROWS, exception.reason)
            assertNotNull(exception.where)
            assertTrue(exception.where!!.isNotEmpty())
        }
    }

    @Test
    fun `should throw ASSERT_FAILURE`() {
        openSession().use { session ->
            val exception = assertFailsWith<RoutineAssertionException> {
                session.createNativeQuery("""
                    DO $$
                    BEGIN
                        ASSERT false, 'Assertion failed';
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("P0004", exception.sqlState)
            assertEquals(RoutineAssertionExceptionReason.ASSERT_FAILURE, exception.reason)
            assertNotNull(exception.where)
            assertTrue(exception.where!!.isNotEmpty())
        }
    }
}
