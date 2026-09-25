package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ConstraintViolationExceptionIntegrationTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val schema = """
        CREATE TABLE provinces (id INT PRIMARY KEY);
        CREATE TABLE governors (
            id          INT PRIMARY KEY,
            province_id INT REFERENCES provinces(id),
            cognomen    VARCHAR(50) NOT NULL,
            term_years  INT CHECK (term_years > 0)
        );
        CREATE TABLE garrisons (id INT PRIMARY KEY, province_id INT REFERENCES provinces(id) ON DELETE RESTRICT);
    """.trimIndent()

    @BeforeEach
    fun emptyTables() {
        openSession().use { it.createNativeQuery("TRUNCATE provinces, governors, garrisons").execute() }
    }

    @Test
    fun `should throw UNIQUE_CONSTRAINT_VIOLATION`() {
        openSession().use { session ->
            session.createNativeQuery("INSERT INTO provinces (id) VALUES (1)").execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO provinces (id) VALUES (1)").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION, exception.reason)
            assertEquals("provinces", exception.table)
            assertNotNull(exception.constraint) // Usually provinces_pkey
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION`() {
        openSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO governors (id, province_id, cognomen, term_years) VALUES (1, 999, 'Varus', 5)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("governors", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION deleting a row a NO ACTION key references`() {
        openSession().use { session ->
            session.createNativeQuery("INSERT INTO provinces (id) VALUES (1)").execute()
            session.createNativeQuery("INSERT INTO governors (id, province_id, cognomen, term_years) VALUES (1, 1, 'Varus', 5)")
                .execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("DELETE FROM provinces WHERE id = 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("23503", exception.sqlState)
            assertEquals("governors", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw FOREIGN_KEY_VIOLATION deleting a row a RESTRICT key references`() {
        openSession().use { session ->
            session.createNativeQuery("INSERT INTO provinces (id) VALUES (1)").execute()
            session.createNativeQuery("INSERT INTO garrisons (id, province_id) VALUES (1, 1)").execute()

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("DELETE FROM provinces WHERE id = 1").execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION, exception.reason)
            assertEquals("23001", exception.sqlState)
            assertEquals("garrisons", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw NOT_NULL_VIOLATION`() {
        openSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO governors (id, province_id, cognomen, term_years) VALUES (1, NULL, NULL, 5)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.NOT_NULL_VIOLATION, exception.reason)
            assertEquals("governors", exception.table)
            assertEquals("cognomen", exception.column)
            assertEquals("public", exception.schema)
        }
    }

    @Test
    fun `should throw CHECK_CONSTRAINT_VIOLATION`() {
        openSession().use { session ->

            val exception = assertFailsWith<ConstraintViolationException> {
                session.createNativeQuery("INSERT INTO governors (id, province_id, cognomen, term_years) VALUES (1, NULL, 'Varus', 0)")
                    .execute()
            }
            logger.error(exception) { "" }
            assertEquals(ConstraintViolationExceptionReason.CHECK_CONSTRAINT_VIOLATION, exception.reason)
            assertEquals("governors", exception.table)
            assertNotNull(exception.constraint)
            assertEquals("public", exception.schema)
        }
    }
}
