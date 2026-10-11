package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Several servers that really are what [MultiHostIntegrationTest] can only play: a primary, its streaming standby,
 * and a server of another cluster.
 *
 * Runs only where all three are listening - `TEST_REPLICATION=true`, on 5442, 5443 and 5444, each with the test
 * database and the test credentials. `scripts/replication-test-servers.ps1` stands them up.
 */
@EnabledIfEnvironmentVariable(named = "TEST_REPLICATION", matches = "true")
class ReplicationIntegrationTest {

    enum class Legion { Prima, Secunda }

    private val primaryPort = 5442
    private val standbyPort = 5443
    private val foreignPort = 5444

    private val primary = "localhost:$primaryPort"
    private val standby = "localhost:$standbyPort"
    private val foreign = "localhost:$foreignPort"

    private fun url(servers: String, query: String = "") =
        "jdbc:octavius://$servers/${TestDatabase.DATABASE}?sslmode=disable$query"

    private fun open(url: String): OctaviusSession = getOctaviusSession(url, TestDatabase.properties())

    private fun OctaviusSession.inRecovery(): Boolean = createNativeQuery("SELECT pg_is_in_recovery()").fetchField<Boolean>()

    /** Which cluster the session is on - not the port, which behind a container's port mapping is its own. */
    private fun OctaviusSession.cluster(): Long =
        createNativeQuery("SELECT system_identifier FROM pg_control_system()").fetchField<Long>()

    private fun holderOf(servers: String) = GlobalCatalogStore.holderFor(DatabaseKey.from(OctaviusProperties.parse(url(servers))))

    @AfterEach
    fun forgetTheCatalogs() {
        // The cluster a catalog belongs to is decided by the first server admitted, so no test may inherit one
        GlobalCatalogStore.removeCatalog(url("$primary,$standby"))
        GlobalCatalogStore.removeCatalog(url("$primary,$foreign"))
        GlobalCatalogStore.removeCatalog(url(primary))
        GlobalCatalogStore.removeCatalog(url(foreign))
    }

    @Test
    fun `should settle on the standby for every kind a standby is`() {
        for (target in listOf("standby", "prefer-standby", "read-only")) {
            open(url("$primary,$standby", "&target_session_attrs=$target")).use { session ->
                assertTrue(session.inRecovery(), target)
            }
        }
    }

    @Test
    fun `should settle on the primary for every kind a primary is, with the standby listed first`() {
        for (target in listOf("primary", "read-write")) {
            open(url("$standby,$primary", "&target_session_attrs=$target")).use { session ->
                assertFalse(session.inRecovery(), target)
            }
        }
    }

    @Test
    fun `should admit the primary and its standby into one catalog`() {
        open(url("$primary,$standby", "&target_session_attrs=primary")).use { }
        open(url("$primary,$standby", "&target_session_attrs=standby")).use { }

        val holder = holderOf("$primary,$standby")
        assertNotNull(holder.systemIdentifier)
        assertTrue(holder.isAdmitted(ServerAddress("localhost", primaryPort)))
        assertTrue(holder.isAdmitted(ServerAddress("localhost", standbyPort)))
    }

    @Test
    fun `should use on the standby what was registered through the primary`() {
        open(url(primary)).use { it.createNativeQuery("CREATE TYPE legion AS ENUM ('PRIMA', 'SECUNDA')").execute() }
        try {
            open(url(standby)).use { session ->
                val replayed = (1..100).any {
                    session.createNativeQuery("SELECT to_regtype('legion') IS NOT NULL").fetchField<Boolean>()
                        .also { replayed -> if (!replayed) Thread.sleep(100) }
                }
                if (!replayed) fail("the standby did not replay CREATE TYPE within 10 seconds")
            }

            open(url("$primary,$standby", "&target_session_attrs=primary")).use { session ->
                session.reloadTypes()
                session.typeManager.registerEnum<Legion>()
            }

            val read = open(url("$primary,$standby", "&target_session_attrs=standby")).use { session ->
                assertTrue(session.inRecovery())
                session.createNativeQuery("SELECT 'SECUNDA'::legion").fetchField<Legion>()
            }
            assertEquals(Legion.Secunda, read)
        } finally {
            open(url(primary)).use { it.createNativeQuery("DROP TYPE legion").execute() }
            GlobalCatalogStore.removeCatalog(url(primary))
            GlobalCatalogStore.removeCatalog(url(standby))
        }
    }

    @Test
    fun `should pass over a server of another cluster than the catalog was read from`() {
        val primaryCluster = open(url(primary)).use { it.cluster() }
        // It answers, so what passes it over below is its cluster and nothing else
        val foreignCluster = open(url(foreign)).use { it.cluster() }
        assertNotEquals(primaryCluster, foreignCluster)

        // Listed second, the foreign server is never reached, and the primary decides the cluster
        val first = open(url("$primary,$foreign")).use { it.cluster() }
        // The same servers, so the same catalog - and now the foreign one is tried first
        val second = open(url("$foreign,$primary")).use { it.cluster() }

        assertEquals(primaryCluster, first)
        assertEquals(primaryCluster, second)
        assertFalse(holderOf("$primary,$foreign").isAdmitted(ServerAddress("localhost", foreignPort)))
    }
}
