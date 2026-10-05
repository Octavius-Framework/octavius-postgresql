package io.github.octaviusframework.driver.initialization

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Choosing among several servers: which failures move the search on, which end it, and which kind of server
 * `target_session_attrs` settles for.
 *
 * The test database is a primary, and it is the only real server there is. A standby, a server not taking
 * connections yet, and one that refuses the password are played by a [ScriptedServer], each only ever passed over -
 * a scripted server cannot answer the queries a connection that settled on it would send. [MultiHostReplicationTest]
 * runs the same choices against a real standby and a real second cluster.
 */
class MultiHostIntegrationTest {

    private val database = "${TestDatabase.HOST}:${TestDatabase.PORT}"

    private fun url(servers: String, query: String = "") =
        "jdbc:octavius://$servers/${TestDatabase.DATABASE}?sslmode=disable$query"

    /** A port nothing listens on, so a connection to it is refused. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /** A server that logs anyone in as a PostgreSQL 18 reporting [inHotStandby], or not reporting it when `null`. */
    private fun loginReporting(inHotStandby: String?) = ScriptedServer(
        authenticationOk + parameter("server_version", "18.6") + parameter("search_path", "\"\$user\", public") +
            (inHotStandby?.let { parameter("in_hot_standby", it) } ?: ByteArray(0)) +
            parameter("default_transaction_read_only", "off") + backendKey(32) + readyForQuery
    )

    /** Runs [block] on a session opened on [url], then forgets the catalog its servers made. */
    private fun <T> onSession(url: String, configure: OctaviusProperties.() -> Unit = {}, block: (OctaviusSession) -> T): T =
        try {
            getOctaviusSession(url, TestDatabase.properties(configure)).use(block)
        } finally {
            GlobalCatalogStore.removeCatalog(url)
        }

    private fun refusal(url: String, configure: OctaviusProperties.() -> Unit = {}): InitializationException =
        try {
            assertFailsWith<InitializationException> { getOctaviusSession(url, TestDatabase.properties(configure)).close() }
        } finally {
            GlobalCatalogStore.removeCatalog(url)
        }

    private fun OctaviusSession.serverPort(): Int = createNativeQuery("SELECT inet_server_port()").fetchField<Int>()

    @Test
    fun `should move on to the next server when one does not answer`() {
        val port = onSession(url("127.0.0.1:${closedPort()},$database")) { it.serverPort() }

        assertEquals(TestDatabase.PORT, port)
    }

    @Test
    fun `should pass a standby over for primary`() {
        loginReporting(inHotStandby = "on").use { standby ->
            val port = onSession(url("127.0.0.1:${standby.port},$database", "&target_session_attrs=primary")) { it.serverPort() }

            assertNotEquals(-1, standby.startupVersion, "the standby was never asked")
            assertEquals(TestDatabase.PORT, port)
        }
    }

    @Test
    fun `should refuse a primary for standby, and say why`() {
        val ex = refusal(url(database, "&target_session_attrs=standby"))

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertTrue(ex.details!!.contains("is not in hot standby"), ex.details)
    }

    @Test
    fun `should settle for a primary under prefer-standby when the list has no standby`() {
        val port = onSession(url(database, "&target_session_attrs=prefer-standby")) { it.serverPort() }

        assertEquals(TestDatabase.PORT, port)
    }

    @Test
    fun `should tell read-only from read-write by default_transaction_read_only`() {
        val readOnly: OctaviusProperties.() -> Unit = { additionalProperties["default_transaction_read_only"] = "on" }

        onSession(url(database, "&target_session_attrs=read-only"), readOnly) { session ->
            assertEquals("on", session.createNativeQuery("SHOW default_transaction_read_only").fetchField<String>())
        }
        onSession(url(database, "&target_session_attrs=read-write")) { session ->
            assertEquals("off", session.createNativeQuery("SHOW default_transaction_read_only").fetchField<String>())
        }

        val writable = refusal(url(database, "&target_session_attrs=read-only"))
        assertTrue(writable.details!!.contains("accepts writes"), writable.details)

        val readOnlyRefused = refusal(url(database, "&target_session_attrs=read-write"), readOnly)
        assertTrue(readOnlyRefused.details!!.contains("default_transaction_read_only on"), readOnlyRefused.details)
    }

    @Test
    fun `should move on from a server that takes no connections yet`() {
        ScriptedServer(fatal("57P03", "the database system is starting up")).use { starting ->
            val port = onSession(url("127.0.0.1:${starting.port},$database")) { it.serverPort() }

            assertNotEquals(-1, starting.startupVersion, "the starting server was never asked")
            assertEquals(TestDatabase.PORT, port)
        }
    }

    @Test
    fun `should end the search at a rejected password, which the next server would reject too`() {
        ScriptedServer(fatal("28P01", "password authentication failed for user \"postgres\"")).use { first ->
            loginReporting(inHotStandby = "off").use { second ->
                val ex = refusal(url("127.0.0.1:${first.port},127.0.0.1:${second.port}"))

                assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, ex.reason)
                assertEquals(-1, second.startupVersion, "the search went on past a rejected password")
            }
        }
    }

    @Test
    fun `should refuse a server that does not report in_hot_standby when the kind matters`() {
        loginReporting(inHotStandby = null).use { silent ->
            val ex = refusal(url("127.0.0.1:${silent.port}", "&target_session_attrs=primary"))

            assertEquals(InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER, ex.reason)
            assertTrue(ex.details!!.contains("in_hot_standby"), ex.details)
        }
    }

    @Test
    fun `should list every server tried when none could be used`() {
        val first = closedPort()
        val second = closedPort()

        val ex = refusal(url("127.0.0.1:$first,127.0.0.1:$second"))

        assertEquals(InitializationExceptionReason.CONNECTION_ERROR, ex.reason)
        assertTrue(ex.details!!.contains("127.0.0.1:$first") && ex.details!!.contains("127.0.0.1:$second"), ex.details)
        assertEquals(2, ex.suppressed.size)
    }

    @Test
    fun `should reach the server that answers in whatever order load balancing draws`() {
        val url = url("127.0.0.1:${closedPort()},$database", "&load_balance_hosts=random")

        repeat(4) {
            assertEquals(TestDatabase.PORT, onSession(url) { it.serverPort() })
        }
    }

    @Test
    fun `should share one catalog between the servers whatever order they are listed in`() {
        // Two names for the one test database, so both belong to the cluster the catalog is read from. Each list
        // starts with a different one, so between them both servers are connected to and asked which cluster
        // they are.
        val forward = url("localhost:${TestDatabase.PORT},127.0.0.1:${TestDatabase.PORT}")
        val backward = url("127.0.0.1:${TestDatabase.PORT},localhost:${TestDatabase.PORT}")
        val key = DatabaseKey.from(OctaviusProperties.parse(forward))
        assertEquals(key, DatabaseKey.from(OctaviusProperties.parse(backward)))

        try {
            getOctaviusSession(forward, TestDatabase.properties()).use { }
            getOctaviusSession(backward, TestDatabase.properties()).use { }

            val holder = GlobalCatalogStore.holderFor(key)
            assertNotNull(holder.systemIdentifier)
            assertTrue(holder.isAdmitted(ServerAddress("localhost", TestDatabase.PORT)))
            assertTrue(holder.isAdmitted(ServerAddress("127.0.0.1", TestDatabase.PORT)))
        } finally {
            GlobalCatalogStore.removeCatalog(forward)
        }
    }
}
