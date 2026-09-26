package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional

/**
 * A connection aborted in the middle of a Spring transaction takes the transaction with it, and the
 * pool's proxy answers everything from then on with a bare `SQLException` carrying neither SQLState
 * nor cause. Spring has nothing to translate, so what reaches the caller says only that a JDBC
 * commit failed - and on the rollback path it replaces the exception that caused the rollback.
 */
@SpringBootTest(
    classes = [AbortTestApplication::class, OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class],
    properties = [
        "spring.datasource.url=${TestDatabase.URL}",
        "spring.datasource.username=${TestDatabase.USER}",
        "spring.datasource.password=${TestDatabase.PASSWORD}",
        "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
    ]
)
class OctaviusSpringAbortedConnectionTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var service: AbortService

    @Test
    fun `a commit on a connection that is gone says the connection is gone`() {
        val ex = assertThrows(OctaviusDataAccessException::class.java) { service.abortThenCommit() }

        val octavius = assertInstanceOf<NetworkException>(ex.octaviusException)
        assertEquals(NetworkExceptionReason.CONNECTION_ABORTED, octavius.reason)
    }

    /**
     * The rollback is already satisfied - the server discarded the transaction along with the
     * connection - so raising over the top of it would replace the exception the caller actually
     * needs to see with one about the cleanup.
     */
    @Test
    fun `an application exception survives a rollback on a connection that is gone`() {
        val ex = assertThrows(IllegalStateException::class.java) { service.abortThenThrow() }

        assertEquals("the failure worth reporting", ex.message)
    }

    @Test
    fun `the pool recovers afterwards`() {
        assertEquals(1, octaviusTemplate.execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() })
    }
}

@TestConfiguration
@EnableTransactionManagement
open class AbortTestApplication {

    @Bean
    open fun abortService(octaviusTemplate: OctaviusTemplate): AbortService = AbortService(octaviusTemplate)
}

open class AbortService(private val octaviusTemplate: OctaviusTemplate) {

    @Transactional
    open fun abortThenCommit() {
        octaviusTemplate.execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
        octaviusTemplate.execute { abort() }
        // returns normally, so Spring tries to commit a transaction that no longer has a connection
    }

    @Transactional
    open fun abortThenThrow() {
        octaviusTemplate.execute { abort() }
        throw IllegalStateException("the failure worth reporting")
    }
}
