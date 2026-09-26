package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.dao.DataAccessException
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional

/**
 * Spring drives the connection itself, giving it back through `DataSourceUtils` rather than through
 * the session, so nothing the session left on it used to be undone: `LISTEN` registrations and a
 * hand-written `BEGIN` went back into the pool and became the next borrower's starting state.
 *
 * The pool holds exactly one connection here, so "the next borrower" is the next statement in these
 * tests and anything left behind is visible immediately.
 */
@SpringBootTest(
    classes = [CleanupTestApplication::class, OctaviusSpringAutoConfiguration::class, DataSourceAutoConfiguration::class],
    properties = [
        "spring.datasource.url=${TestDatabase.URL}",
        "spring.datasource.username=${TestDatabase.USER}",
        "spring.datasource.password=${TestDatabase.PASSWORD}",
        "spring.datasource.driver-class-name=io.github.octaviusframework.driver.jdbc.OctaviusDriver",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1"
    ]
)
class OctaviusSpringSessionCleanupTest {

    @Autowired
    lateinit var octaviusTemplate: OctaviusTemplate

    @Autowired
    lateinit var service: CleanupService

    private fun listeningChannels(): Long =
        octaviusTemplate.execute { createNativeQuery("SELECT count(*) FROM pg_listening_channels()").fetchFieldStrict() }

    private fun backendPid(): Int =
        octaviusTemplate.execute { createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict() }

    /**
     * Puts the one connection in the pool back to a known state by hand, so that each test fails on
     * its own assertion rather than on whatever the test before it left behind - which is the whole
     * point here, since these tests are about state that outlives a connection's borrower.
     *
     * Some of that state cannot be cleaned with a statement: a connection left mid-`COPY` refuses
     * everything. That one is dropped instead, which is what a session closing over it would do.
     */
    @BeforeEach
    fun `start every test on a connection carrying nothing`() {
        try {
            octaviusTemplate.execute {
                createNativeQuery("ROLLBACK").execute()
                createNativeQuery("UNLISTEN *").execute()
            }
        } catch (_: DataAccessException) {
            runCatching { octaviusTemplate.execute { abort() } }
        }
        service.createTable()
    }

    // ------------------------------------------------------------- outside a transaction

    @Test
    fun `a subscription made outside a transaction does not outlive the call that made it`() {
        val pid = backendPid()
        octaviusTemplate.execute { notifications.listen("spring_cleanup_chan") }

        assertEquals(pid, backendPid(), "expected the same physical connection back")
        assertEquals(0L, listeningChannels())
    }

    @Test
    fun `a hand-written BEGIN outside a transaction does not follow the connection back`() {
        octaviusTemplate.execute {
            createNativeQuery("BEGIN").execute() // the driver is never told
            createNativeQuery("INSERT INTO spring_cleanup (val) VALUES ('abandoned')").update()
        }

        // Rolled back rather than committed: the driver has no idea what that work was.
        val count = octaviusTemplate.execute {
            createNativeQuery("SELECT count(*) FROM spring_cleanup").fetchFieldStrict<Long>()
        }
        assertEquals(0L, count)
    }

    /**
     * A transfer nobody ended cannot be reset away - ending a `COPY OUT` means reading the rest of
     * the export first - so the connection leaves the pool instead, the same trade a session closed
     * by hand makes.
     */
    @Test
    fun `a COPY left open inside a block costs the connection rather than poisoning the pool`() {
        val pid = backendPid()

        octaviusTemplate.execute {
            val copyIn = copy.copyIn("COPY spring_cleanup (val) FROM STDIN WITH (FORMAT CSV)")
            copyIn.writeToCopy("abandoned\n".toByteArray())
            // neither endCopy() nor cancelCopy()
        }

        assertNotEquals(pid, backendPid(), "a connection in copy mode was handed to the next borrower")
        val count = octaviusTemplate.execute {
            createNativeQuery("SELECT count(*) FROM spring_cleanup").fetchFieldStrict<Long>()
        }
        assertEquals(0L, count, "a COPY IN that never reached endCopy() must land nothing")
    }

    // -------------------------------------------------------------- inside a transaction

    @Test
    fun `a subscription made inside a transaction is undone when the transaction commits`() {
        service.subscribeAndCommit("spring_cleanup_committed")

        assertEquals(0L, listeningChannels())
    }

    /**
     * A guard rather than a proof: `LISTEN` takes effect at commit, so one inside a transaction that
     * rolls back never takes effect at all and there is nothing here for the cleanup to undo.
     * Measured on PostgreSQL 18 - `BEGIN; LISTEN a; ROLLBACK` leaves `pg_listening_channels()` empty.
     * What this pins down is that the cleanup does not make that worse.
     */
    @Test
    fun `a subscription made inside a transaction that rolls back leaves nothing behind`() {
        runCatching { service.subscribeAndRollback("spring_cleanup_rolled_back") }

        assertEquals(0L, listeningChannels())
    }

    /**
     * The case that decides where in Spring's completion sequence the cleanup hangs.
     *
     * A transaction that failed on the server is still failed when `beforeCompletion` runs, and
     * PostgreSQL ignores every statement until the block ends - `25P02`, measured. A reset attempted
     * there would raise, and a session that cannot reset its connection gives the connection up
     * instead, so every failed transaction would cost one. `afterCompletion` runs once the rollback
     * is through and the connection answers again.
     */
    @Test
    fun `a transaction that failed on the server does not cost its connection`() {
        val pid = backendPid()

        runCatching { service.subscribeThenFailOnTheServer("spring_cleanup_failed") }

        assertEquals(pid, backendPid(), "the connection was given up rather than reset")
        assertEquals(0L, listeningChannels())
    }

    @Test
    fun `the session lasts as long as the transaction, not as long as one execute call`() {
        val (first, second) = service.twoCallsInOneTransaction()

        assertSame(first, second, "each execute opened a session of its own over the same connection")
    }

    @Test
    fun `two calls outside a transaction get a session each`() {
        val first = octaviusTemplate.execute { this }
        val second = octaviusTemplate.execute { this }

        assertNotSame(first, second)
    }

    @Test
    fun `work from both calls of one transaction commits together`() {
        service.insertTwiceInOneTransaction()

        val count = octaviusTemplate.execute {
            createNativeQuery("SELECT count(*) FROM spring_cleanup").fetchFieldStrict<Long>()
        }
        assertEquals(2L, count, "the connection was given up between the two calls")
    }
}

@TestConfiguration
@EnableTransactionManagement
open class CleanupTestApplication {

    @Bean
    open fun cleanupService(octaviusTemplate: OctaviusTemplate): CleanupService = CleanupService(octaviusTemplate)
}

open class CleanupService(private val octaviusTemplate: OctaviusTemplate) {

    open fun createTable() {
        octaviusTemplate.execute {
            createNativeQuery("CREATE TABLE IF NOT EXISTS spring_cleanup (id SERIAL PRIMARY KEY, val TEXT)").execute()
            createNativeQuery("TRUNCATE spring_cleanup").execute()
        }
    }

    @Transactional
    open fun subscribeAndCommit(channel: String) {
        octaviusTemplate.execute { notifications.listen(channel) }
    }

    @Transactional
    open fun subscribeAndRollback(channel: String) {
        octaviusTemplate.execute { notifications.listen(channel) }
        throw RuntimeException("Rollback")
    }

    @Transactional
    open fun subscribeThenFailOnTheServer(channel: String) {
        octaviusTemplate.execute {
            notifications.listen(channel)
            createNativeQuery("SELECT 1 / 0").execute() // leaves the transaction in a failed state
        }
    }

    @Transactional
    open fun twoCallsInOneTransaction(): Pair<OctaviusSessionOperations, OctaviusSessionOperations> {
        val first = octaviusTemplate.execute { this }
        val second = octaviusTemplate.execute { this }
        return first to second
    }

    @Transactional
    open fun insertTwiceInOneTransaction() {
        octaviusTemplate.execute { createNativeQuery("INSERT INTO spring_cleanup (val) VALUES ('one')").update() }
        octaviusTemplate.execute { createNativeQuery("INSERT INTO spring_cleanup (val) VALUES ('two')").update() }
    }
}
