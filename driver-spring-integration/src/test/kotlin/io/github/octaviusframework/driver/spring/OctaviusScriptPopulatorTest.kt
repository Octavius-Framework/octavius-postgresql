package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.DataException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
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

    /** Runs [sql] the way `@Sql` runs a script in a `@Transactional` test: on the connection `DataSourceUtils` finds. */
    private fun runScript(sql: String, dataSource: DataSource) =
        ResourceDatabasePopulator(ByteArrayResource(sql.toByteArray())).execute(dataSource)

    private fun tables(vararg names: String): List<String> = TestDatabase.openSession().use {
        it.createNativeQuery("SELECT tablename::text FROM pg_tables WHERE schemaname = 'public' ORDER BY 1")
            .fetchFields<String>()
            .filter { name -> name in names }
    }

    @Test
    fun `a script run inside a transaction rolls back with it`() {
        TestDatabase.dataSource().use { dataSource ->
            TransactionTemplate(OctaviusJdbcTransactionManager(dataSource)).executeWithoutResult { status ->
                runScript("CREATE TABLE cohors (numerus int)", dataSource)
                status.setRollbackOnly()
            }

            assertEquals(emptyList(), tables("cohors"), "the script ran on a connection of its own and committed")
        }
    }

    @Test
    fun `a script run around a REQUIRES_NEW transaction lands in the transaction it ran in`() {
        TestDatabase.dataSource().use { dataSource ->
            val manager = OctaviusJdbcTransactionManager(dataSource)
            val requiresNew = TransactionTemplate(manager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }

            TransactionTemplate(manager).executeWithoutResult { status ->
                requiresNew.executeWithoutResult { runScript("CREATE TABLE centuria (numerus int)", dataSource) }
                runScript("CREATE TABLE manipulus (numerus int)", dataSource)
                status.setRollbackOnly()
            }

            assertEquals(listOf("centuria"), tables("centuria", "manipulus"))
        }
    }
}
