package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `execute`, the terminal for statements whose rows nobody reads. */
class ExecuteTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `execute refuses a statement that returns rows`() {
        openSession().use { session ->
            val exception = assertFailsWith<InvalidOperationException> {
                session.createNativeQuery("SELECT 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(InvalidOperationExceptionReason.UNEXPECTED_RESULT, exception.reason)
        }
    }

    @Test
    fun `execute told to ignore the rows discards them`() {
        openSession().use { session ->
            session.createNativeQuery("SELECT 1").execute(ignoreRows = true)

            // The session still works, which is the half worth asserting: the rows were drained on the way
            // to ReadyForQuery rather than left on the socket for the next statement to trip over.
            assertEquals(42, session.createNativeQuery("SELECT 42").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `a whole script runs past a statement in it that returns rows`() {
        openSession().use { session ->
            // What a script written elsewhere looks like: pg_dump puts a SELECT pg_catalog.setval(...) after
            // every sequence.
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
}
