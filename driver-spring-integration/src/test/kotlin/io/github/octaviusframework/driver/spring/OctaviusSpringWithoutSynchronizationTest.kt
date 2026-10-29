package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * A transaction manager with synchronization switched off still begins and commits a transaction of its own, but
 * gives [OctaviusTemplate] nothing to bind a session to: each `execute` opens a session over the transaction's
 * connection and closes it as the call returns, with the transaction still running.
 */
class OctaviusSpringWithoutSynchronizationTest {

    @Test
    fun `work done through the template is committed with the transaction`() {
        TestDatabase.dataSource().use { dataSource ->
            val template = OctaviusTemplate(dataSource)
            template.execute {
                createNativeQuery("CREATE TABLE IF NOT EXISTS spring_no_sync (id INT)").execute()
                createNativeQuery("TRUNCATE spring_no_sync").execute()
            }
            val manager = OctaviusJdbcTransactionManager(dataSource).apply {
                transactionSynchronization = AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER
            }

            TransactionTemplate(manager).executeWithoutResult {
                template.execute { createNativeQuery("INSERT INTO spring_no_sync VALUES (1)").executeUpdate() }
            }

            val count = template.execute { createNativeQuery("SELECT count(*) FROM spring_no_sync").fetchFieldStrict<Long>() }
            template.execute { createNativeQuery("DROP TABLE spring_no_sync").execute() }
            assertEquals(1L, count, "closing the call's session rolled back the transaction Spring then committed")
        }
    }
}
