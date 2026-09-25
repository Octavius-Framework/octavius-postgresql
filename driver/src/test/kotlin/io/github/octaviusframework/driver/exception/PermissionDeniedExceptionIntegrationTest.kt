package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PermissionDeniedExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /** A foreigner: allowed into the city, not into the treasury. */
    private fun peregrinusSession() = openSession {
        user = "peregrinus"
        password = "sine_civitate"
    }

    override val schema = "CREATE TABLE aerarium (id INT, sestertii BIGINT)"

    @BeforeEach
    fun setup() {
        openSession().use { session ->
            try { session.createNativeQuery("DROP OWNED BY peregrinus").execute() } catch (e: Exception) {}
            try { session.createNativeQuery("DROP USER IF EXISTS peregrinus").execute() } catch (e: Exception) {}
            session.createNativeQuery("CREATE USER peregrinus WITH PASSWORD 'sine_civitate'").execute()
            session.createNativeQuery("GRANT USAGE ON SCHEMA public TO peregrinus").execute()
            session.createNativeQuery("REVOKE ALL PRIVILEGES ON TABLE aerarium FROM peregrinus").execute()
        }
    }

    @AfterEach
    fun teardown() {
        openSession().use { session ->
            try {
                session.createNativeQuery("REVOKE USAGE ON SCHEMA public FROM peregrinus").execute()
            } catch (e: Exception) {}
            try {
                session.createNativeQuery("DROP USER IF EXISTS peregrinus").execute()
            } catch (e: Exception) {}
        }
    }

    @Test
    fun `should throw PermissionDeniedException with table and schema details`() {
        peregrinusSession().use { session ->
            val exception = assertFailsWith<PermissionDeniedException> {
                session.createNativeQuery("SELECT * FROM aerarium").fetchRows()
            }
            logger.error(exception) { "" }
            assertEquals("42501", exception.sqlState)
            println("errorMessage: " + exception.dbMessage)
            println("table: " + exception.table)
            println("schema: " + exception.schema)
            assertTrue(exception.dbMessage.contains("aerarium"))
        }
    }
}
