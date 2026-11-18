package io.github.octaviusframework.client.dynamic

import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.serializer.octaviusJson
import io.github.octaviusframework.serializer.octaviusSnakeCaseJson
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `dynamic_dto` registrations made through more than one client on the same database.
 *
 * The names are the database's: every client on it reads the ones any of them registered, and each class keeps
 * the terms it was registered on whichever client the query goes through. Every test starts from a dropped
 * registry and drops it again on the way out, since the registry lives in the driver's catalog and would
 * otherwise carry one test's registrations into the next.
 */
class DynamicTypesAcrossClientsTest : AbstractIntegrationTest() {

    @Serializable
    data class Grant(val province: String, val iugera: Int)

    @Serializable
    data class Citation(val text: String)

    @Serializable
    data class Stipend(val provinceName: String, val annualAmount: Int)

    override val schema = DYNAMIC_DTO_DDL

    @BeforeEach
    @AfterEach
    fun dropRegistry() {
        GlobalCatalogStore.removeCatalog(TestDatabase.URL)
    }

    /** Runs [block] with two clients on the same database, each on a pool of its own. */
    private fun withTwoClients(
        firstJson: Json = octaviusSnakeCaseJson,
        block: (OctaviusClient, OctaviusClient) -> Unit
    ) {
        TestDatabase.dataSource().use { dsA ->
            TestDatabase.dataSource().use { dsB ->
                OctaviusClient.fromDataSource(dsA, dynamicJson = firstJson).use { a ->
                    OctaviusClient.fromDataSource(dsB).use { b -> block(a, b) }
                }
            }
        }
    }

    private val grantSql = "SELECT dynamic_dto('grant', jsonb_build_object('province', 'Asia', 'iugera', 7))"

    // --- One set of names per database ------------------------------------------------------------

    @Test
    fun `a name registered through one client reads back through both after the other registers its own`() {
        // The case that failed while each client kept names of its own: the second client's converter claimed
        // every dynamic_dto asked for as Any, and knew only its own names.
        withTwoClients { a, b ->
            a.dynamicTypes.register<Grant>("grant")
            b.dynamicTypes.register<Citation>("citation")

            assertEquals(Grant("Asia", 7), a.rawQuery(grantSql).fetchFieldStrict<Any>())
            assertEquals(Grant("Asia", 7), b.rawQuery(grantSql).fetchFieldStrict<Any>())
        }
    }

    @Test
    fun `a class registered through one client is written through the other`() {
        withTwoClients { a, b ->
            a.dynamicTypes.register<Grant>("grant")
            b.dynamicTypes.register<Citation>("citation")

            // An array element, whose type the array declares - the write that failed the same way.
            val grants = listOf(Grant("Asia", 7), Grant("Gallia", 120))
            assertEquals(
                grants,
                b.rawQuery("SELECT @xs::public.dynamic_dto[]").fetchFieldStrict<List<Grant>>("xs" to grants)
            )
            assertEquals(Citation("ob civem servatum"), a.rawQuery("SELECT @x").fetchFieldStrict<Any>("x" to Citation("ob civem servatum")))
        }
    }

    @Test
    fun `the same class under the same name from both clients is harmless`() {
        withTwoClients { a, b ->
            a.dynamicTypes.register<Grant>("grant")
            b.dynamicTypes.register<Grant>("grant")

            assertEquals(Grant("Asia", 7), b.rawQuery(grantSql).fetchFieldStrict<Grant>())
        }
    }

    // --- What is refused, now that the names are shared -------------------------------------------

    @Test
    fun `a name taken on the database is refused to another client's class`() {
        withTwoClients { a, b ->
            a.dynamicTypes.register<Grant>("grant")

            val thrown = assertFailsWith<InvalidOperationException> { b.dynamicTypes.register<Citation>("grant") }
            assertTrue(thrown.details!!.contains("Grant") && thrown.details!!.contains("Citation"), thrown.details)
        }
    }

    object Elsewhere {
        @Serializable
        data class Grant(val province: String, val iugera: Int)
    }

    @Test
    fun `two classes of one simple name are told apart in the refusal`() {
        withTwoClients { a, b ->
            a.dynamicTypes.register<Grant>("grant")

            val thrown = assertFailsWith<InvalidOperationException> { b.dynamicTypes.register<Elsewhere.Grant>("grant") }
            assertTrue(
                thrown.details!!.contains(Grant::class.qualifiedName!!) &&
                    thrown.details!!.contains(Elsewhere.Grant::class.qualifiedName!!),
                thrown.details
            )
        }
    }

    @Test
    fun `one class cannot take two names`() {
        withTwoClients { a, _ ->
            a.dynamicTypes.register<Grant>("grant")

            val thrown = assertFailsWith<InvalidOperationException> { a.dynamicTypes.register<Grant>("land_grant") }
            assertTrue(thrown.details!!.contains("'grant'"), thrown.details)
        }
    }

    // --- The terms travel with the class ----------------------------------------------------------

    @Test
    fun `a payload is read with the Json its class was registered with, whichever client reads it`() {
        // b's own Json keys in snake_case, so only a's can read this payload.
        withTwoClients(firstJson = octaviusJson) { a, b ->
            a.dynamicTypes.register<Stipend>("stipend")
            b.dynamicTypes.register<Citation>("citation")

            val payload = "jsonb_build_object('provinceName', 'Aegyptus', 'annualAmount', 500)"
            assertEquals(
                Stipend("Aegyptus", 500),
                b.rawQuery("SELECT dynamic_dto('stipend', $payload)").fetchFieldStrict<Any>()
            )
        }
    }

    // --- Reading without a registration -----------------------------------------------------------

    @Test
    fun `the raw form reads a name nothing was registered under`() {
        withTwoClients { a, _ ->
            a.dynamicTypes.register<Grant>("grant")

            val raw = a.rawQuery("SELECT dynamic_dto('unregistered', '{}'::jsonb)").fetchFieldStrict<DynamicDto>()
            assertEquals("unregistered", raw.typeName)
        }
    }
}
