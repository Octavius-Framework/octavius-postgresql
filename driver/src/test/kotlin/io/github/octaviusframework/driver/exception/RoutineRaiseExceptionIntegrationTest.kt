package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoutineRaiseExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `should throw RoutineRaiseException for a plain RAISE EXCEPTION`() {
        openSession().use { session ->
            val exception = assertFailsWith<RoutineRaiseException> {
                session.createNativeQuery("""
                    DO $$
                    BEGIN
                        RAISE EXCEPTION 'Test exception' USING DETAIL = 'The augury was unfavourable', HINT = 'Try tomorrow';
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("P0001", exception.sqlState)
            assertEquals("Test exception", exception.dbMessage)
            assertEquals("The augury was unfavourable", exception.dbDetail)
            assertEquals("Try tomorrow", exception.hint)
            assertNotNull(exception.where)
            assertTrue(exception.where!!.isNotEmpty())
        }
    }

    // P0000 is reachable only by asking for it, which makes it another deliberate raise rather than an
    // unclassified failure - the reason this class has no reason enum to put it in.
    @Test
    fun `should throw RoutineRaiseException for a raise carrying the generic PL pgSQL code`() {
        openSession().use { session ->
            val exception = assertFailsWith<RoutineRaiseException> {
                session.createNativeQuery("""
                    DO $$
                    BEGIN
                        RAISE EXCEPTION 'Generic failure' USING ERRCODE = 'plpgsql_error';
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("P0000", exception.sqlState)
            assertEquals("Generic failure", exception.dbMessage)
        }
    }

    // USING ERRCODE reaching outside class P0 leaves this exception entirely, which is what makes a domain
    // SQLSTATE worth raising.
    @Test
    fun `should route a raise carrying a foreign SQLSTATE by that code instead`() {
        openSession().use { session ->
            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("""
                    DO $$
                    BEGIN
                        RAISE EXCEPTION 'Already enrolled' USING ERRCODE = '23505';
                    END;
                    $$;
                """).execute()
            }
            logger.error(exception) { "" }
            assertEquals("23505", exception.sqlState)
        }
    }
}
