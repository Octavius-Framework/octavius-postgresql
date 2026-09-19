package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConstraintViolationExceptionIntegrationTest {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private fun getSession() =
        getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
        })

    @BeforeEach
    fun setup() {
        getSession().use { session ->
            session.createNativeQuery("CREATE TABLE IF NOT EXISTS parent_table (id INT PRIMARY KEY)").execute()
            session.createNativeQuery(
                """
            CREATE TABLE IF NOT EXISTS constraint_test_table (
                id INT PRIMARY KEY,
                parent_id INT REFERENCES parent_table(id),
                not_null_col VARCHAR(50) NOT NULL,
                check_col INT CHECK (check_col > 0)
            )
        """
            ).execute()
            session.createNativeQuery(
                "CREATE TABLE IF NOT EXISTS restrict_test_table (id INT PRIMARY KEY, parent_id INT REFERENCES parent_table(id) ON DELETE RESTRICT)"
            ).execute()
        }
    }

    @AfterEach
    fun teardown() {
        getSession().use { session ->
            session.createNativeQuery("DROP TABLE IF EXISTS constraint_test_table").execute()
            session.createNativeQuery("DROP TABLE IF EXISTS restrict_test_table").execute()
            session.createNativeQuery("DROP TABLE IF EXISTS parent_table").execute()
        }
    }

    @Test
    fun `should throw UNIQUE_CONSTRAINT_VIOLATION`() {
        getSession().use { session ->
            session.createNativeQuery("INSERT INTO parent_table (id) VALUES (1)").execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO parent_table (id) VALUES (1)").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION, exception.reason)
            assertEquals("parent_table", exception.table)
            assertNotNull(exception.constraint) // Usually parent_table_pkey
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION`() {
        getSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO constraint_test_table (id, parent_id, not_null_col, check_col) VALUES (1, 999, 'test', 5)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("constraint_test_table", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION deleting a row a NO ACTION key references`() {
        getSession().use { session ->
            session.createNativeQuery("INSERT INTO parent_table (id) VALUES (1)").execute()
            session.createNativeQuery("INSERT INTO constraint_test_table (id, parent_id, not_null_col, check_col) VALUES (1, 1, 'test', 5)")
                .execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("DELETE FROM parent_table WHERE id = 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("23503", exception.sqlState)
            assertEquals("constraint_test_table", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION deleting a row a RESTRICT key references`() {
        getSession().use { session ->
            session.createNativeQuery("INSERT INTO parent_table (id) VALUES (1)").execute()
            session.createNativeQuery("INSERT INTO restrict_test_table (id, parent_id) VALUES (1, 1)").execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("DELETE FROM parent_table WHERE id = 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("23001", exception.sqlState)
            assertEquals("restrict_test_table", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw NOT_NULL_VIOLATION`() {
        getSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO constraint_test_table (id, parent_id, not_null_col, check_col) VALUES (1, NULL, NULL, 5)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.NOT_NULL_VIOLATION, exception.reason)
            assertEquals("constraint_test_table", exception.table)
            assertEquals("not_null_col", exception.column)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw CHECK_CONSTRAINT_VIOLATION`() {
        getSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO constraint_test_table (id, parent_id, not_null_col, check_col) VALUES (1, NULL, 'test', 0)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.CHECK_CONSTRAINT_VIOLATION, exception.reason)
            assertEquals("constraint_test_table", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }
}
