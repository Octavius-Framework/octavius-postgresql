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

    private fun getNoPermSession() = openSession {
        user = "test_user_no_perms"
        password = "password"
    }

    @BeforeEach
    fun setup() {
        openSession().use { session ->
            try { session.createNativeQuery("DROP OWNED BY test_user_no_perms").execute() } catch (e: Exception) {}
            try { session.createNativeQuery("DROP USER IF EXISTS test_user_no_perms").execute() } catch (e: Exception) {}
            session.createNativeQuery("CREATE USER test_user_no_perms WITH PASSWORD 'password'").execute()
            session.createNativeQuery("GRANT USAGE ON SCHEMA public TO test_user_no_perms").execute()
            session.createNativeQuery("CREATE TABLE IF NOT EXISTS perm_test_table (id INT)").execute()
            session.createNativeQuery("REVOKE ALL PRIVILEGES ON TABLE perm_test_table FROM test_user_no_perms").execute()
        }
    }

    @AfterEach
    fun teardown() {
        openSession().use { session ->
            session.createNativeQuery("DROP TABLE IF EXISTS perm_test_table").execute()
            try {
                session.createNativeQuery("REVOKE USAGE ON SCHEMA public FROM test_user_no_perms").execute()
            } catch (e: Exception) {}
            try {
                session.createNativeQuery("DROP USER IF EXISTS test_user_no_perms").execute()
            } catch (e: Exception) {}
        }
    }

    @Test
    fun `should throw PermissionDeniedException with table and schema details`() {
        getNoPermSession().use { session ->
            val exception = assertFailsWith<PermissionDeniedException> {
                session.createNativeQuery("SELECT * FROM perm_test_table").fetchRows()
            }
            logger.error(exception) { "" }
            assertEquals("42501", exception.sqlState)
            println("errorMessage: " + exception.dbMessage)
            println("table: " + exception.table)
            println("schema: " + exception.schema)
            assertTrue(exception.dbMessage.contains("perm_test_table"))
        }
    }
}
