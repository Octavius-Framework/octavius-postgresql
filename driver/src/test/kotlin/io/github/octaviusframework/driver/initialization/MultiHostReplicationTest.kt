package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.driver.exception.ExecutionAbortedExceptionReason
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Choosing among several servers when they are real ones: a primary, a streaming standby of it, and a primary of a
 * cluster of its own - what [MultiHostIntegrationTest] can only play with a [ScriptedServer], here a server the
 * session settles on and runs queries against.
 *
 * Runs only where `TEST_MULTIHOST=true` and the three servers are up - `scripts/multihost-servers.sh` starts them,
 * on `MULTIHOST_PRIMARY_PORT`, `MULTIHOST_STANDBY_PORT` and `MULTIHOST_FOREIGN_PORT` or 5434, 5435 and 5436, each
 * with the test database and credentials of [TestDatabase].
 *
 * Every case starts with no catalog for any list of them, so which server a catalog is read from is the case's own
 * doing.
 */
@EnabledIfEnvironmentVariable(named = "TEST_MULTIHOST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiHostReplicationTest {

    enum class Legion { Prima, Secunda }

    private val primary = "127.0.0.1:${port("MULTIHOST_PRIMARY_PORT", 5434)}"
    private val standby = "127.0.0.1:${port("MULTIHOST_STANDBY_PORT", 5435)}"
    private val foreign = "127.0.0.1:${port("MULTIHOST_FOREIGN_PORT", 5436)}"

    private fun port(variable: String, default: Int) = System.getenv(variable)?.toInt() ?: default

    private fun url(vararg servers: String, query: String = "") =
        "jdbc:octavius://${servers.joinToString(",")}/${TestDatabase.DATABASE}?sslmode=disable$query"

    /** Every URL a case opened a session on, so its catalog goes with the case. */
    private val opened = mutableListOf<String>()

    private fun open(url: String, configure: OctaviusProperties.() -> Unit = {}): OctaviusSession {
        opened += url
        return getOctaviusSession(url, TestDatabase.properties(configure))
    }

    private fun refusal(url: String): InitializationException {
        opened += url
        return assertFailsWith<InitializationException> { getOctaviusSession(url, TestDatabase.properties()).close() }
    }

    @AfterEach
    fun forgetCatalogs() {
        opened.forEach { GlobalCatalogStore.removeCatalog(it) }
        opened.clear()
    }

    private fun address(server: String) = ServerAddress(server.substringBefore(':'), server.substringAfter(':').toInt())

    /** A port nothing listens on. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * Which of the three servers the session settled on. Each listens on 5432 inside its container, so the port the
     * server sees is no help; the script names each by its `cluster_name`.
     */
    private fun OctaviusSession.server(): String = createNativeQuery("SHOW cluster_name").fetchField<String>()
    private fun OctaviusSession.inRecovery(): Boolean = createNativeQuery("SELECT pg_is_in_recovery()").fetchField<Boolean>()


    @BeforeAll
    fun createTheTypeOnThePrimary() {
        // On the primary alone - a list of one, so a catalog of its own - and before any catalog of a list is read,
        // so each one read later has the type in it.
        val single = url(primary)
        try {
            getOctaviusSession(single, TestDatabase.properties()).use { session ->
                session.createNativeQuery(
                    "DROP TYPE IF EXISTS legion; CREATE TYPE legion AS ENUM ('PRIMA', 'SECUNDA')"
                ).execute()
            }
        } finally {
            GlobalCatalogStore.removeCatalog(single)
        }

        // The standby has it once it has replayed that far.
        val onStandby = url(standby)
        try {
            getOctaviusSession(onStandby, TestDatabase.properties()).use { session ->
                val deadline = System.nanoTime() + 30_000_000_000L
                while (!session.createNativeQuery("SELECT to_regtype('public.legion') IS NOT NULL").fetchField<Boolean>()) {
                    check(System.nanoTime() < deadline) { "The standby did not replay the type within 30 seconds" }
                    Thread.sleep(100)
                }
            }
        } finally {
            GlobalCatalogStore.removeCatalog(onStandby)
        }
    }

    @Test
    fun `the servers are what the cases take them for`() {
        open(url(primary)).use { assertFalse(it.inRecovery(), "$primary is expected to be a primary") }
        open(url(standby)).use { assertTrue(it.inRecovery(), "$standby is expected to be a standby") }
        open(url(foreign)).use { assertFalse(it.inRecovery(), "$foreign is expected to be a primary") }

        val identifier = "SELECT system_identifier FROM pg_control_system()"
        val ofPrimary = open(url(primary)).use { it.createNativeQuery(identifier).fetchField<Long>() }
        assertEquals(ofPrimary, open(url(standby)).use { it.createNativeQuery(identifier).fetchField<Long>() })
        assertNotEquals(ofPrimary, open(url(foreign)).use { it.createNativeQuery(identifier).fetchField<Long>() })
    }

    @Test
    fun `should pass the standby over for primary and read-write, whichever comes first`() {
        for (attrs in listOf("primary", "read-write")) {
            for (order in listOf(arrayOf(standby, primary), arrayOf(primary, standby))) {
                open(url(*order, query = "&target_session_attrs=$attrs")).use { session ->
                    assertEquals("primary", session.server(), "$attrs over ${order.toList()}")
                    assertFalse(session.inRecovery())
                }
            }
        }
    }

    @Test
    fun `should pass the primary over for standby and read-only, whichever comes first`() {
        for (attrs in listOf("standby", "read-only", "prefer-standby")) {
            for (order in listOf(arrayOf(primary, standby), arrayOf(standby, primary))) {
                open(url(*order, query = "&target_session_attrs=$attrs")).use { session ->
                    assertEquals("standby", session.server(), "$attrs over ${order.toList()}")
                    assertTrue(session.inRecovery())
                }
            }
        }
    }

    @Test
    fun `should take the first server that answers under any`() {
        open(url(standby, primary)).use { assertEquals("standby", it.server()) }
        open(url("127.0.0.1:${closedPort()}", primary, standby)).use { assertEquals("primary", it.server()) }
    }

    @Test
    fun `should settle for the primary under prefer-standby when the standby does not answer`() {
        open(url("127.0.0.1:${closedPort()}", primary, query = "&target_session_attrs=prefer-standby")).use { session ->
            assertEquals("primary", session.server())
        }
    }

    @Test
    fun `should refuse writes on the standby it settled on`() {
        open(url(primary, standby, query = "&target_session_attrs=standby")).use { session ->
            val ex = assertFailsWith<OctaviusException> {
                session.createNativeQuery("CREATE TABLE senatus (id int)").execute()
            }
            assertEquals("25006", ex.sqlState, "read_only_sql_transaction")
        }
    }

    @Test
    fun `should list both servers when neither is the kind asked for`() {
        val ex = refusal(url(primary, foreign, query = "&target_session_attrs=standby"))

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertTrue(ex.details!!.contains(primary) && ex.details!!.contains(foreign), ex.details)
        assertTrue(ex.details!!.contains("is not in hot standby"), ex.details)
    }

    @Test
    fun `should reach both servers under load balancing`() {
        val url = url(primary, standby, query = "&load_balance_hosts=random")
        val reached = (1..40).map { open(url).use { it.server() } }.toSet()

        // Each draw is one of two, so forty draws that miss either are one in 2^39.
        assertEquals(setOf("primary", "standby"), reached)
    }

    @Test
    fun `should share the catalog read on the primary with its standby`() {
        val forPrimary = url(primary, standby, query = "&target_session_attrs=primary")
        val forStandby = url(primary, standby, query = "&target_session_attrs=standby")
        val key = DatabaseKey.from(OctaviusProperties.parse(forPrimary))
        assertEquals(key, DatabaseKey.from(OctaviusProperties.parse(forStandby)))

        open(forPrimary).use { session ->
            session.typeManager.registerEnum<Legion>()
            assertEquals(Legion.Secunda, session.createNativeQuery("SELECT 'SECUNDA'::legion").fetchField<Legion>())
        }

        // Registered once, on the primary: the standby's session reads it from the same catalog.
        open(forStandby).use { session ->
            assertTrue(session.inRecovery())
            assertEquals(Legion.Prima, session.createNativeQuery("SELECT 'PRIMA'::legion").fetchField<Legion>())
            assertTrue(session.createNativeQuery("SELECT pg_typeof($1) = 'legion'::regtype").fetchField<Boolean>(Legion.Secunda))
        }

        val holder = GlobalCatalogStore.holderFor(key)
        assertTrue(holder.isAdmitted(address(primary)))
        assertTrue(holder.isAdmitted(address(standby)))
    }

    @Test
    fun `should pass over a server of another cluster than the catalog was read from`() {
        // The first session reads the catalog from the primary; the second lists the foreign server first.
        open(url(primary, foreign)).use { assertEquals("primary", it.server()) }

        open(url(foreign, primary)).use { session ->
            assertEquals("primary", session.server())
        }

        val holder = GlobalCatalogStore.holderFor(DatabaseKey.from(OctaviusProperties.parse(url(primary, foreign))))
        assertTrue(holder.isAdmitted(address(primary)))
        assertFalse(holder.isAdmitted(address(foreign)))
    }

    @Test
    fun `should refuse when the only server of the kind asked for is of another cluster`() {
        // The catalog is read from the standby; the one primary left in the list is the foreign one.
        open(url(foreign, standby, query = "&target_session_attrs=standby")).use { assertTrue(it.inRecovery()) }

        val ex = refusal(url(foreign, standby, query = "&target_session_attrs=primary"))

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertTrue(ex.details!!.contains("system identifier"), ex.details)
        assertTrue(ex.details!!.contains("is in hot standby"), ex.details)
    }

    @Test
    fun `should cancel a query on the server the session settled on`() {
        // The standby is listed second, so a cancel sent to the first server would reach the wrong one.
        open(url(primary, standby, query = "&target_session_attrs=standby")).use { session ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor.submit {
                    Thread.sleep(300)
                    session.cancelQuery()
                }

                val ex = assertFailsWith<ExecutionAbortedException> {
                    session.createNativeQuery("SELECT pg_sleep(10)").fetchRowStrict()
                }

                assertEquals(ExecutionAbortedExceptionReason.QUERY_CANCELED, ex.reason)
                assertEquals("standby", session.server())
            } finally {
                executor.shutdown()
            }
        }
    }
}
