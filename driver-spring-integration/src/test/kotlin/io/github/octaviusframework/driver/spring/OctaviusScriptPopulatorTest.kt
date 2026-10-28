package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.DataException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Spring's script runner - what `spring.sql.init` runs `schema.sql` and `data.sql` through - over the driver's
 * `Statement`. It recognizes a failed statement only as a `SQLException`, so that is what decides whether
 * `continue-on-error` carries on and whether the failure names its script and statement.
 */
class OctaviusScriptPopulatorTest : AbstractIntegrationTest() {

    private val script = """
        CREATE TABLE legio (numerus int);
        INSERT INTO legio VALUES ('decima');
        INSERT INTO legio VALUES (10);
    """.trimIndent()

    private fun <T> withConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.URL, TestDatabase.USER, TestDatabase.PASSWORD).use(block)

    private fun populator(continueOnError: Boolean) =
        ResourceDatabasePopulator(ByteArrayResource(script.toByteArray(), "legio.sql")).apply {
            setContinueOnError(continueOnError)
        }

    @Test
    fun `continue on error carries on past a statement the server rejects`() {
        withConnection { connection ->
            connection.createStatement().execute("DROP TABLE IF EXISTS legio")
            populator(continueOnError = true).populate(connection)

            val rows = TestDatabase.openSession().use {
                it.createNativeQuery("SELECT count(*) FROM legio").fetchFieldStrict<Long>()
            }
            assertEquals(1L, rows)
        }
    }

    @Test
    fun `without it the failure names its statement and keeps the driver's exception`() {
        withConnection { connection ->
            connection.createStatement().execute("DROP TABLE IF EXISTS legio")
            val failure = assertFailsWith<ScriptStatementFailedException> { populator(continueOnError = false).populate(connection) }
            assertContains(failure.message.orEmpty(), "statement #2")
            val wrapper = assertIs<SQLExceptionWrapper>(failure.cause)
            assertIs<DataException>(wrapper.wrappedException)
            assertEquals("22P02", wrapper.sqlState)
        }
    }

    @Test
    fun `a script run inside a transaction rolls back with it`() {
        TestDatabase.dataSource().use { dataSource ->
            TransactionTemplate(OctaviusJdbcTransactionManager(dataSource)).executeWithoutResult { status ->
                // What @Sql does in a @Transactional test: the populator takes its connection through DataSourceUtils
                ResourceDatabasePopulator(ByteArrayResource("CREATE TABLE cohors (numerus int)".toByteArray())).execute(dataSource)
                status.setRollbackOnly()
            }

            val tables = TestDatabase.openSession().use {
                it.createNativeQuery("SELECT count(*) FROM pg_tables WHERE tablename = 'cohors'").fetchFieldStrict<Long>()
            }
            assertEquals(0L, tables, "the script ran on a connection of its own and committed")
        }
    }
}
