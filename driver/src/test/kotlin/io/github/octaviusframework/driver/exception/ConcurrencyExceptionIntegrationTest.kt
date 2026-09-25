package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConcurrencyExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @BeforeEach
    fun setup() {
        openSession().use { session ->
            session.createNativeQuery("CREATE TABLE IF NOT EXISTS lock_test_table (id INT PRIMARY KEY)").execute()
            session.createNativeQuery("INSERT INTO lock_test_table (id) VALUES (1) ON CONFLICT DO NOTHING").execute()
        }
    }

    @AfterEach
    fun teardown() {
        openSession().use { session ->
            session.createNativeQuery("DROP TABLE IF EXISTS lock_test_table").execute()
        }
    }

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
        openSession().use { session1 ->
            openSession().use { session2 ->
                // Start transaction in session1 and lock the row
                session1.createNativeQuery("BEGIN").execute()
                session1.createNativeQuery("SELECT * FROM lock_test_table WHERE id = 1 FOR UPDATE").fetchRows()

                try {
                    val exception = assertFailsWith<ConcurrencyException> {
                        // Try to lock the same row with NOWAIT in session2
                        session2.createNativeQuery("SELECT * FROM lock_test_table WHERE id = 1 FOR UPDATE NOWAIT")
                            .fetchRows()
                    }
                    logger.error(exception) { "" }
                    assertEquals(ConcurrencyExceptionReason.LOCK_NOT_AVAILABLE, exception.reason)
                } finally {
                    session1.createNativeQuery("ROLLBACK").execute()
                }
            }
        }
    }
}
