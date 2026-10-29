package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.client.DataResult
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.dbResult
import io.github.octaviusframework.client.transaction.TransactionPlan
import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DATASOURCE_USERNAME = "spring.datasource.username=${TestDatabase.USER}"
private const val DATASOURCE_PASSWORD = "spring.datasource.password=${TestDatabase.PASSWORD}"
private const val DATASOURCE_DRIVER = "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver"

/**
 * The client over [SpringSessionProvider]: its queries and transactions run in the ones Spring manages, and
 * `asResult()`, `dbResult`, `transactionResult` and plans behave as they do without Spring.
 */
@ImportAutoConfiguration(OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class)
@SpringBootTest(
    classes = [ClientTestApplication::class],
    properties = [TEST_DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD, DATASOURCE_DRIVER]
)
class OctaviusSpringClientTest {

    @Autowired
    lateinit var db: OctaviusClient

    @Autowired
    lateinit var service: ClientService

    @BeforeEach
    fun createTable() {
        TestDatabase.openSession().use {
            it.createNativeQuery("CREATE TABLE IF NOT EXISTS spring_client (name TEXT PRIMARY KEY)").execute()
            it.createNativeQuery("TRUNCATE spring_client").execute()
        }
    }

    @AfterEach
    fun dropTable() {
        TestDatabase.openSession().use { it.createNativeQuery("DROP TABLE IF EXISTS spring_client").execute() }
    }

    private fun committedNames(): List<String> = TestDatabase.openSession().use {
        it.createNativeQuery("SELECT name FROM spring_client ORDER BY name").fetchFields()
    }

    @Test
    fun `a query inside @Transactional rolls back with it`() {
        assertThrows<IllegalStateException> { service.insertThenThrow() }

        assertEquals(emptyList<String>(), committedNames())
    }

    @Test
    fun `a REQUIRED transaction joins the one Spring is running`() {
        assertThrows<IllegalStateException> { service.clientTransactionThenThrow(TransactionPropagation.REQUIRED) }

        assertEquals(emptyList<String>(), committedNames())
    }

    @Test
    fun `a REQUIRES_NEW transaction commits apart from the one Spring is running`() {
        assertThrows<IllegalStateException> { service.clientTransactionThenThrow(TransactionPropagation.REQUIRES_NEW) }

        assertEquals(listOf("inner"), committedNames())
    }

    @Test
    fun `a failed NESTED transaction rolls back to its savepoint, and the one around it goes on`() {
        service.failedNestedThenInsert()

        assertEquals(listOf("after", "outer"), committedNames())
    }

    @Test
    fun `isolation and read-only reach the transaction Spring begins`() {
        val settings = db.transaction(isolation = TransactionIsolationLevel.SERIALIZABLE, readOnly = true) {
            rawQuery(
                "SELECT current_setting('transaction_isolation') AS isolation, " +
                    "current_setting('transaction_read_only') AS read_only"
            ).fetchRowStrict()
        }

        assertEquals("serializable", settings.get<String>("isolation"))
        assertEquals("on", settings.get<String>("read_only"))
    }

    @Test
    fun `transactionResult rolls back over a returned failure and hands it back`() {
        val result = db.transactionResult {
            val insert = rawQuery("INSERT INTO spring_client (name) VALUES (@name)")
            insert.asResult().executeUpdate("name" to "first")
            insert.asResult().executeUpdate("name" to "first")
        }

        val failure = assertInstanceOf<DataResult.Failure>(result)
        assertInstanceOf<ConstraintViolationException>(failure.error)
        assertEquals(emptyList<String>(), committedNames())
    }

    @Test
    fun `a plan whose step fails leaves nothing behind, and dbResult returns the failure`() {
        val plan = TransactionPlan()
        val name = plan.add(
            db.rawQuery("INSERT INTO spring_client (name) VALUES (@name) RETURNING name")
                .asStep().fetchFieldStrict<String>("name" to "planned")
        )
        plan.add(db.rawQuery("INSERT INTO spring_client (name) VALUES (@name)").asStep().executeUpdate("name" to name.value()))

        val failure = assertInstanceOf<DataResult.Failure>(dbResult { db.executeTransactionPlan(plan) })

        assertInstanceOf<ConstraintViolationException>(failure.error)
        assertEquals(listOf("step 1"), failure.error.path)
        assertEquals(emptyList<String>(), committedNames())
    }

    @Test
    fun `a statement timeout cancels a statement in a transaction the client began`() {
        val aborted = assertThrows<ExecutionAbortedException> {
            db.transaction(statementTimeout = 100.milliseconds) { rawQuery("SELECT pg_sleep(1)").fetchRows() }
        }

        assertEquals("57014", aborted.sqlState)
    }

    @Test
    fun `a transaction that joined leaves no statement timeout over the rest of the one it joined`() {
        // pg_sleep(0.3) runs after a joined transaction asked for 100 ms
        assertEquals(1, service.sleepAfterJoiningWithATimeout())
    }
}

/**
 * The client's `transaction_timeout` over connections that start every transaction with one of their own, as a
 * database or role default would.
 */
@ImportAutoConfiguration(OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class)
@SpringBootTest(
    classes = [ClientTestApplication::class],
    properties = [
        TEST_DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD, DATASOURCE_DRIVER,
        "spring.datasource.hikari.connection-init-sql=SET transaction_timeout = '200ms'"
    ]
)
class OctaviusSpringClientOverADefaultTest {

    @Autowired
    lateinit var db: OctaviusClient

    @Test
    fun `a transaction timeout replaces the one its connection starts transactions with`() {
        // Half a second within a timeout of two, past the connection's 200 ms
        val one = db.transaction(transactionTimeout = 2.seconds) {
            rawQuery("SELECT pg_sleep(0.5)").fetchRows()
            rawQuery("SELECT 1").fetchFieldStrict<Int>()
        }

        assertEquals(1, one)
    }
}

@Configuration
@EnableTransactionManagement
open class ClientTestApplication {

    @Bean
    open fun octaviusClient(template: OctaviusTemplate, transactionManager: PlatformTransactionManager): OctaviusClient =
        OctaviusClient.fromSessionProvider(SpringSessionProvider(template, transactionManager))

    @Bean
    open fun clientService(db: OctaviusClient): ClientService = ClientService(db)
}

open class ClientService(private val db: OctaviusClient) {

    private fun insert(name: String) =
        db.rawQuery("INSERT INTO spring_client (name) VALUES (@name)").executeUpdate("name" to name)

    @Transactional
    open fun insertThenThrow() {
        insert("outer")
        throw IllegalStateException("rolled back")
    }

    @Transactional
    open fun clientTransactionThenThrow(propagation: TransactionPropagation) {
        insert("outer")
        db.transaction(propagation) { insert("inner") }
        throw IllegalStateException("rolled back")
    }

    @Transactional
    open fun failedNestedThenInsert() {
        insert("outer")
        // A duplicate key aborts what is under the savepoint, and only that
        assertThrows<ConstraintViolationException> { db.transaction(TransactionPropagation.NESTED) { insert("outer") } }
        insert("after")
    }

    @Transactional
    open fun sleepAfterJoiningWithATimeout(): Int {
        db.transaction(statementTimeout = 100.milliseconds) { rawQuery("SELECT 1").fetchFieldStrict<Int>() }
        db.rawQuery("SELECT pg_sleep(0.3)").fetchRows()
        return db.rawQuery("SELECT 1").fetchFieldStrict()
    }
}
