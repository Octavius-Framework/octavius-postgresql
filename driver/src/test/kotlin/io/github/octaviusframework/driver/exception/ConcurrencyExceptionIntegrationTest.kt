package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConcurrencyExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val schema = """
        CREATE TABLE granaries (id INT PRIMARY KEY, city TEXT NOT NULL);
        INSERT INTO granaries VALUES (1, 'Ostia');
    """.trimIndent()

    @Test
    fun `should throw TIMEOUT`() {
        openSession().use { session ->
            session.createNativeQuery("SET statement_timeout = '10ms'").execute()

            val exception = assertFailsWith<ExecutionAbortedException> {
                session.createNativeQuery("SELECT pg_sleep(1)").fetchField<Any?>()
            }
            logger.error(exception) { "" }
            assertEquals("57014", exception.sqlState)
            assertEquals(ExecutionAbortedExceptionReason.QUERY_CANCELED, exception.reason)
        }
    }

    @Test
    fun `should throw LOCK_NOT_AVAILABLE`() {
        openSession().use { quaestor ->
            openSession().use { aedile ->
                // One magistrate takes the granary at Ostia and holds it
                quaestor.createNativeQuery("BEGIN").execute()
                quaestor.createNativeQuery("SELECT * FROM granaries WHERE id = 1 FOR UPDATE").fetchRows()

                try {
                    val exception = assertFailsWith<ConcurrencyException> {
                        // and the other will not wait for it
                        aedile.createNativeQuery("SELECT * FROM granaries WHERE id = 1 FOR UPDATE NOWAIT")
                            .fetchRows()
                    }
                    logger.error(exception) { "" }
                    assertEquals(ConcurrencyExceptionReason.LOCK_NOT_AVAILABLE, exception.reason)
                } finally {
                    quaestor.createNativeQuery("ROLLBACK").execute()
                }
            }
        }
    }
}
