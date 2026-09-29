package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional

/**
 * What `spring.datasource.hikari.auto-commit: false` does to the two ways of writing, as the Spring page
 * describes it: a `@Transactional` method commits as it would anyway, and a write through [OctaviusTemplate]
 * outside one lands in a transaction nobody commits, and is rolled back when its session closes.
 *
 * Counted from a session of the test's own, outside the pool, so what it sees is what was committed.
 */
@SpringBootTest(
    classes = [AutoCommitOffTestApplication::class, OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class],
    properties = [
        "spring.datasource.url=${TestDatabase.URL}",
        "spring.datasource.username=${TestDatabase.USER}",
        "spring.datasource.password=${TestDatabase.PASSWORD}",
        "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.auto-commit=false"
    ]
)
class OctaviusSpringAutoCommitOffTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var service: AutoCommitOffService

    private fun committedRows(): Long = TestDatabase.openSession().use {
        it.createNativeQuery("SELECT count(*) FROM spring_autocommit_off").fetchFieldStrict()
    }

    @BeforeEach
    fun createTable() {
        TestDatabase.openSession().use {
            it.createNativeQuery("CREATE TABLE IF NOT EXISTS spring_autocommit_off (id INT)").execute()
            it.createNativeQuery("TRUNCATE spring_autocommit_off").execute()
        }
    }

    @AfterEach
    fun dropTable() {
        TestDatabase.openSession().use { it.createNativeQuery("DROP TABLE IF EXISTS spring_autocommit_off").execute() }
    }

    @Test
    fun `a write outside a transaction is rolled back when its session closes`() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO spring_autocommit_off VALUES (1)").update() }

        assertEquals(0L, committedRows())
        // Nor did it wait on the connection for the next borrower to commit it.
        service.insertInTransaction(2)
        assertEquals(1L, committedRows())
    }

    @Test
    fun `a write inside a transaction commits as it would anyway`() {
        service.insertInTransaction(1)

        assertEquals(1L, committedRows())
    }
}

@TestConfiguration
@EnableTransactionManagement
open class AutoCommitOffTestApplication {

    @Bean
    open fun autoCommitOffService(octaviusTemplate: OctaviusTemplate): AutoCommitOffService =
        AutoCommitOffService(octaviusTemplate)
}

open class AutoCommitOffService(private val octaviusTemplate: OctaviusTemplate) {

    @Transactional
    open fun insertInTransaction(id: Int) {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO spring_autocommit_off VALUES ($1)").update(id) }
    }
}
