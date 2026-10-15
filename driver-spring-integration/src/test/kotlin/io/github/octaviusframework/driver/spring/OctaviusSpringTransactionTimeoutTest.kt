package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.SessionTerminatedExceptionReason
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.testsupport.TestDatabase
import io.github.octaviusframework.testsupport.assumeUnreadFatalSurvives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

private const val DATASOURCE_URL = TEST_DATASOURCE_URL
private const val DATASOURCE_USERNAME = "spring.datasource.username=${TestDatabase.USER}"
private const val DATASOURCE_PASSWORD = "spring.datasource.password=${TestDatabase.PASSWORD}"
private const val DATASOURCE_DRIVER = "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver"

// One connection, so a backend pid that stays the same across calls is the connection surviving.
private const val ONE_CONNECTION = "spring.datasource.hikari.maximum-pool-size=1"
private const val KEPT_OPEN = "spring.datasource.hikari.minimum-idle=1"

/**
 * `@Transactional(timeout = …)` under the default [TransactionTimeoutScope.STATEMENT]: each statement is held to
 * the timeout by the server and cancelled past it, the transaction as a whole by Spring's deadline, and the
 * connection outlives both.
 */
@SpringBootTest(
    classes = [TimeoutTestApplication::class, OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class],
    properties = [DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD, DATASOURCE_DRIVER, ONE_CONNECTION, KEPT_OPEN]
)
class OctaviusSpringTransactionTimeoutTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var service: TimeoutService

    private fun backendPid(): Int = octaviusTemplate.execute { createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>() }

    private fun setting(name: String): String = octaviusTemplate.execute { createNativeQuery("SHOW $name").fetchFieldStrict<String>() }

    @Test
    fun `a transaction without a timeout sends none`() {
        assertEquals("0", service.settingWithoutTimeout("statement_timeout"))
    }

    @Test
    fun `a transaction's timeout bounds its statements, and ends with it`() {
        assertEquals("1s", service.settingWithinOneSecond("statement_timeout"))
        assertEquals("0", service.settingWithinOneSecond("transaction_timeout"))

        // SET LOCAL: nothing follows the connection out of the transaction
        assertEquals("0", setting("statement_timeout"))
    }

    @Test
    fun `a statement that runs past the timeout is cancelled, and the connection stays`() {
        val before = backendPid()

        val ex = assertThrows(OctaviusDataAccessException::class.java) { service.sleepWithinOneSecond() }

        val aborted = assertInstanceOf<ExecutionAbortedException>(ex.octaviusException)
        assertEquals("57014", aborted.sqlState)
        assertEquals(before, backendPid())
    }

    @Test
    fun `a block started past the deadline is refused`() {
        assertThrows(TransactionTimedOutException::class.java) { service.secondBlockAfter(millis = 1_500) }
    }

    @Test
    fun `an application exception survives a rollback that finds the session ended by the server`() {
        val ex = assertThrows(IllegalStateException::class.java) { service.terminatedThenThrow() }

        assertEquals("the failure worth reporting", ex.message)
        // and the pool has replaced the connection it lost
        assertEquals(1, octaviusTemplate.execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() })
    }

    @Test
    fun `a commit on a session the server ended says why it did not happen`() {
        assumeUnreadFatalSurvives()
        val ex = assertThrows(OctaviusDataAccessException::class.java) { service.terminatedThenCommit() }

        val terminated = assertInstanceOf<SessionTerminatedException>(ex.octaviusException)
        assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, terminated.reason)
    }
}

/**
 * [TransactionTimeoutScope.TRANSACTION], chosen through a customizer the way an application would keep the
 * auto-configured manager, with the timeout itself coming from `spring.transaction.default-timeout`.
 */
@SpringBootTest(
    classes = [
        TransactionScopeTestApplication::class,
        TransactionManagerCustomizationAutoConfiguration::class,
        OctaviusSpringAutoConfiguration::class,
        DataSourceAutoConfiguration::class
    ],
    properties = [
        DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD, DATASOURCE_DRIVER, ONE_CONNECTION, KEPT_OPEN,
        "spring.transaction.default-timeout=2s"
    ]
)
class OctaviusSpringTransactionScopeTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    lateinit var service: TimeoutService

    private fun backendPid(): Int = octaviusTemplate.execute { createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>() }

    @Test
    fun `Boot's customizers reach the auto-configured manager`() {
        val manager = assertInstanceOf<OctaviusJdbcTransactionManager>(transactionManager)
        assertEquals(TransactionTimeoutScope.TRANSACTION, manager.timeoutScope)
        assertEquals(2, manager.defaultTimeout)
    }

    @Test
    fun `the default timeout goes out as transaction_timeout`() {
        assertEquals("2s", service.settingWithoutTimeout("transaction_timeout"))
        assertEquals("0", service.settingWithoutTimeout("statement_timeout"))
    }

    @Test
    fun `a statement that runs past the timeout ends the session, and the caller hears the timeout`() {
        val before = backendPid()

        val ex = assertThrows(OctaviusDataAccessException::class.java) { service.sleepWithinOneSecond() }

        val terminated = assertInstanceOf<SessionTerminatedException>(ex.octaviusException)
        assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, terminated.reason)
        // The connection went with the session, and the pool opened another
        assertNotEquals(before, backendPid())
    }

    @Test
    fun `a transaction left idle past the timeout fails its commit with the timeout`() {
        assumeUnreadFatalSurvives()
        val ex = assertThrows(OctaviusDataAccessException::class.java) { service.idlePastTheTimeoutWithinOneSecond() }

        val terminated = assertInstanceOf<SessionTerminatedException>(ex.octaviusException)
        assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, terminated.reason)
    }
}

@TestConfiguration
@EnableTransactionManagement
open class TimeoutTestApplication {

    @Bean
    open fun timeoutService(octaviusTemplate: OctaviusTemplate): TimeoutService = TimeoutService(octaviusTemplate)
}

@TestConfiguration
@EnableTransactionManagement
open class TransactionScopeTestApplication {

    @Bean
    open fun timeoutService(octaviusTemplate: OctaviusTemplate): TimeoutService = TimeoutService(octaviusTemplate)

    @Bean
    open fun transactionScope() = TransactionManagerCustomizer<OctaviusJdbcTransactionManager> {
        it.timeoutScope = TransactionTimeoutScope.TRANSACTION
    }
}

open class TimeoutService(private val octaviusTemplate: OctaviusTemplate) {

    private fun setting(name: String): String =
        octaviusTemplate.execute { createNativeQuery("SHOW $name").fetchFieldStrict<String>() }

    private fun backendPid(): Int =
        octaviusTemplate.execute { createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>() }

    @Transactional
    open fun settingWithoutTimeout(name: String): String = setting(name)

    @Transactional(timeout = 1)
    open fun settingWithinOneSecond(name: String): String = setting(name)

    @Transactional(timeout = 1)
    open fun sleepWithinOneSecond() {
        octaviusTemplate.execute { createNativeQuery("SELECT pg_sleep(5)").fetchRows() }
    }

    @Transactional(timeout = 1)
    open fun secondBlockAfter(millis: Long) {
        octaviusTemplate.execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
        Thread.sleep(millis)
        octaviusTemplate.execute { createNativeQuery("SELECT 1").fetchFieldStrict<Int>() }
    }

    @Transactional(timeout = 1)
    open fun idlePastTheTimeoutWithinOneSecond() {
        awaitGone(backendPid())
        // returns normally, so Spring commits a transaction the server has already ended
    }

    @Transactional
    open fun terminatedThenThrow() {
        terminate(backendPid())
        throw IllegalStateException("the failure worth reporting")
    }

    @Transactional
    open fun terminatedThenCommit() {
        terminate(backendPid())
    }

    private fun terminate(pid: Int) {
        TestDatabase.openSession().use { it.createNativeQuery("SELECT pg_terminate_backend($1)").fetchFieldStrict<Boolean>(pid) }
        awaitGone(pid)
    }

    /** Waits until the backend has left `pg_stat_activity` - by then it has sent its FATAL. */
    private fun awaitGone(pid: Int) {
        TestDatabase.openSession().use { observer ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (observer.createNativeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = $1").fetchFieldStrict<Long>(pid) > 0) {
                if (System.nanoTime() > deadline) fail("backend $pid never went away")
                Thread.sleep(20)
            }
        }
    }
}
