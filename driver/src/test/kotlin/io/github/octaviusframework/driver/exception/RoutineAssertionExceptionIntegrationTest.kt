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
                        dictator TEXT;
                    BEGIN
                        -- a year with no dictator named
                        SELECT name INTO STRICT dictator FROM (VALUES ('Cincinnatus')) AS dictators(name) WHERE false;
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
                        consul TEXT;
                    BEGIN
                        -- a year always has two, and STRICT wants one
                        SELECT name INTO STRICT consul FROM (VALUES ('Caesar'), ('Bibulus')) AS consuls(name);
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
                        ASSERT false, 'The auspices were not taken';
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
