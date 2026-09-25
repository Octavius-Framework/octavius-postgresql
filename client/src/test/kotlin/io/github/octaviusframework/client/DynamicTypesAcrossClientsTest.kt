package io.github.octaviusframework.client

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.dynamic.DynamicDto
import io.github.octaviusframework.client.dynamic.DynamicWriteStrategy
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.serializer.octaviusJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
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
class DynamicTypesAcrossClientsTest {

    @Serializable
    data class Grant(val province: String, val iugera: Int)

    @Serializable
    data class Citation(val text: String)

    @Serializable
    data class Stipend(val provinceName: String, val annualAmount: Int)

    companion object {
        private const val URL = "jdbc:octavius://localhost:5432/octavius_test"

        private fun dataSource(): HikariDataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = URL
            username = "postgres"
            password = "1234"
            maximumPoolSize = 2
        })

        @BeforeAll
        @JvmStatic
        fun installType() {
            GlobalCatalogStore.removeCatalog(URL)
            dataSource().use { ds -> OctaviusClient.fromDataSource(ds).use { it.dynamicTypes.install() } }
            GlobalCatalogStore.removeCatalog(URL)
        }
    }

    @BeforeEach
    @AfterEach
    fun dropRegistry() {
        GlobalCatalogStore.removeCatalog(URL)
    }

    /** Runs [block] with two clients on the same database, each on a pool of its own. */
    private fun withTwoClients(
        first: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
        second: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS,
        firstJson: Json = octaviusJson,
        block: (OctaviusClient, OctaviusClient) -> Unit
    ) {
        dataSource().use { dsA ->
            dataSource().use { dsB ->
                OctaviusClient.fromDataSource(dsA, dynamicJson = firstJson, dynamicWriteStrategy = first).use { a ->
                    OctaviusClient.fromDataSource(dsB, dynamicWriteStrategy = second).use { b -> block(a, b) }
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

    @Test
    fun `one class cannot take two names`() {
        withTwoClients { a, _ ->
            a.dynamicTypes.register<Grant>("grant")

            val thrown = assertFailsWith<InvalidOperationException> { a.dynamicTypes.register<Grant>("land_grant") }
            assertTrue(thrown.details!!.contains("'grant'"), thrown.details)
        }
    }

    @Test
    fun `a class registered again on another write strategy is refused`() {
        withTwoClients(second = DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { a, b ->
            a.dynamicTypes.register<Grant>("grant")

            val thrown = assertFailsWith<InvalidOperationException> { b.dynamicTypes.register<Grant>("grant") }
            assertTrue(
                thrown.details!!.contains("AUTOMATIC_WHEN_UNAMBIGUOUS") && thrown.details!!.contains("PREFER_DYNAMIC_DTO"),
                thrown.details
            )
        }
    }

    // --- The terms travel with the class ----------------------------------------------------------

    @Test
    fun `a class is written on the strategy it was registered on, whichever client writes it`() {
        withTwoClients(first = DynamicWriteStrategy.EXPLICIT_ONLY) { a, b ->
            a.dynamicTypes.register<Grant>("grant")
            b.dynamicTypes.register<Citation>("citation")

            val thrown = assertFailsWith<MappingException> {
                b.rawQuery("SELECT @x").fetchFieldStrict<Any>("x" to Grant("Asia", 7))
            }
            assertTrue(thrown.details.contains("toDynamicDto"), thrown.details)

            // And the other way round: b's class unwrapped through a, which writes its own on EXPLICIT_ONLY.
            assertEquals(Citation("ob civem servatum"), a.rawQuery("SELECT @x").fetchFieldStrict<Any>("x" to Citation("ob civem servatum")))
        }
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `a payload is read with the Json its class was registered with, whichever client reads it`() {
        val snakeCase = Json(octaviusJson) { namingStrategy = JsonNamingStrategy.SnakeCase }
        withTwoClients(firstJson = snakeCase) { a, b ->
            a.dynamicTypes.register<Stipend>("stipend")
            b.dynamicTypes.register<Citation>("citation")

            assertEquals(
                Stipend("Aegyptus", 500),
                b.rawQuery("SELECT dynamic_dto('stipend', jsonb_build_object('province_name', 'Aegyptus', 'annual_amount', 500))")
                    .fetchFieldStrict<Any>()
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
