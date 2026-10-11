package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.driver.exception.ExecutionAbortedExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The driver through PgBouncer, which speaks protocol 3.0 to its clients whatever the server behind it speaks.
 *
 * Runs only where a PgBouncer 1.23 or later is listening in front of the test database - `TEST_PGBOUNCER=true`,
 * on `PGBOUNCER_PORT` or 6432. In transaction pooling it also has to track `search_path`, which 1.26 does by
 * default and earlier versions do with it in `track_extra_parameters`: untracked, a `SET search_path` is never
 * reported back, and the test that sets one sees the path it had before.
 */
@EnabledIfEnvironmentVariable(named = "TEST_PGBOUNCER", matches = "true")
class PgBouncerIntegrationTest : AbstractIntegrationTest() {

    enum class Rank { Consul, Praetor }

    private val url = "jdbc:octavius://${TestDatabase.HOST}:${System.getenv("PGBOUNCER_PORT") ?: "6432"}/${TestDatabase.DATABASE}"

    // The same name in two schemas, so which one an unqualified name means is up to the search path.
    override val schema = """
        CREATE SCHEMA curia;
        CREATE TYPE curia.rank AS ENUM ('CONSUL', 'PRAETOR');
        CREATE SCHEMA senatus;
        CREATE TYPE senatus.rank AS ENUM ('CONSUL', 'PRAETOR');
    """.trimIndent()

    @BeforeAll
    fun forgetThePoolersCatalog() {
        // The reset forgets the catalog of the database as reached directly; through the pooler it is a
        // different host and port, and so a catalog of its own.
        GlobalCatalogStore.removeCatalog(url)
    }

    private fun openPooledSession(): OctaviusSession = getOctaviusSession(url, TestDatabase.properties())

    @Test
    fun `should open a session and run a query through the pooler`() {
        openPooledSession().use { session ->
            val version = session.createNativeQuery("SELECT current_setting('server_version_num')::int").fetchField<Int>()

            assertTrue(version >= 180000, "the pooler is expected in front of PostgreSQL 18, found $version")
        }
    }

    @Test
    fun `should follow a search_path set through the pooler`() {
        openPooledSession().use { session ->
            session.createNativeQuery("SET search_path TO senatus, public").execute()

            assertEquals(listOf("senatus", "public"), session.searchPath)
        }
    }

    @Test
    fun `should resolve an unqualified type name against the search path the pooler reported`() {
        openPooledSession().use { session ->
            session.createNativeQuery("SET search_path TO senatus, public").execute()
            session.typeManager.registerEnum<Rank>()

            val boundAsSenatus = session.createNativeQuery("SELECT pg_typeof($1) = 'senatus.rank'::regtype")
                .fetchField<Boolean>(Rank.Praetor)

            assertTrue(boundAsSenatus)
        }
    }

    @Test
    fun `should hold a transaction its first statement began through the pooler`() {
        // The BEGIN, the terms and the first statement reach the pooler in one write, each answered by a
        // ReadyForQuery of its own; the transaction has to keep its server across all three and past them.
        openPooledSession().use { session ->
            val (timeout, oneTransaction) = session.transaction.required(statementTimeout = 7.seconds) {
                val first = createNativeQuery("SELECT pg_current_xact_id()::text").fetchField<String>()
                val timeout = createNativeQuery("SELECT current_setting('statement_timeout')").fetchField<String>()
                val last = createNativeQuery("SELECT pg_current_xact_id()::text").fetchField<String>()
                timeout to (first == last)
            }

            assertEquals("7s", timeout)
            assertTrue(oneTransaction)
            assertEquals("0", session.createNativeQuery("SELECT current_setting('statement_timeout')").fetchField<String>())
        }
    }

    @Test
    fun `should refuse a term through the pooler and carry on`() {
        openPooledSession().use { session ->
            // Past the int milliseconds statement_timeout is kept in, so the SET LOCAL behind the BEGIN is refused.
            val thrown = assertFailsWith<OctaviusException> {
                session.transaction.required(statementTimeout = 30.days) {
                    createNativeQuery("SELECT 1").fetchField<Int>()
                }
            }

            assertEquals("22023", thrown.sqlState)
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchField<Int>())
        }
    }

    @Test
    fun `should cancel a query through the pooler`() {
        // Over 3.0 the cancel key is four bytes rather than 3.2's thirty-two, and it is PgBouncer's own,
        // which it maps back to the server's.
        openPooledSession().use { session ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    Thread.sleep(200)
                    session.cancelQuery()
                }

                val ex = assertFailsWith<ExecutionAbortedException> {
                    session.createNativeQuery("SELECT pg_sleep(5)").fetchRowStrict()
                }

                assertEquals(ExecutionAbortedExceptionReason.QUERY_CANCELED, ex.reason)
                assertEquals(1, session.createNativeQuery("SELECT 1").fetchField<Int>())
            } finally {
                executor.shutdown()
            }
        }
    }
}
