package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriverExceptionIntegrationTest : AbstractIntegrationTest() {

    companion object {
        val logger = KotlinLogging.logger {}
    }

    @Test
    fun `should throw InvalidOperationException when calling execute on query that returns rows`() {
        openSession().use { session ->
            val exception = assertFailsWith<InvalidOperationException> {
                session.createNativeQuery("SELECT 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(InvalidOperationExceptionReason.UNEXPECTED_RESULT, exception.reason)
        }
    }

    @Test
    fun `should discard the rows when execute is told to ignore them`() {
        openSession().use { session ->
            session.createNativeQuery("SELECT 1").execute(ignoreRows = true)

            // The session still works, which is the half worth asserting: the rows were drained on the way
            // to ReadyForQuery rather than left on the socket for the next statement to trip over.
            assertEquals(42, session.createNativeQuery("SELECT 42").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `should run a whole script past a statement that returns rows`() {
        openSession().use { session ->
            // What a script written elsewhere looks like: pg_dump puts a SELECT pg_catalog.setval(...) after
            // every sequence, and one of those in the middle used to take the whole call down.
            session.createNativeQuery(
                """
                CREATE TEMP TABLE legio (id int);
                INSERT INTO legio VALUES (1);
                SELECT 'a stray select in the middle';
                INSERT INTO legio VALUES (2)
                """.trimIndent()
            ).execute(ignoreRows = true)

            assertEquals(2, session.createNativeQuery("SELECT count(*) FROM legio").fetchFieldStrict<Long>())
        }
    }

    @Test
    fun `should throw InitializationException for invalid credentials`() {
        val exception = assertFailsWith<InitializationException> {
            openSession { password = "wrong_password" }
        }
        logger.error(exception) { "" }
        assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, exception.reason)
        assertEquals("28P01", exception.sqlState) // Invalid password state
    }

    @Test
    fun `should throw InitializationException with CONNECTION_ERROR for unreachable host`() {
        val exception = assertFailsWith<InitializationException> {
            getOctaviusSession("jdbc:octavius://${TestDatabase.HOST}:54321/${TestDatabase.DATABASE}", TestDatabase.properties())
        }
        logger.error(exception) { "" }
        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, exception.reason)
    }

    @Test
    fun `should throw InvalidOperationException with INCORRECT_RESULT_SIZE for fetchRowStrict on empty result`() {
        openSession().use { session ->
            val exception = assertFailsWith<InvalidOperationException> {
                session.createNativeQuery("SELECT 1 WHERE false").fetchRowStrict()
            }
            logger.error(exception) { "" }
            assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, exception.reason)
        }
    }
    
    @Test
    fun `should throw InvalidOperationException with INCORRECT_RESULT_SIZE for fetchRow on multiple results`() {
        openSession().use { session ->
            val exception = assertFailsWith<InvalidOperationException> {
                session.createNativeQuery("SELECT 1 UNION ALL SELECT 2").fetchRow()
            }
            logger.error(exception) { "" }
            assertEquals(InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE, exception.reason)
        }
    }
}
