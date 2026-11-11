package io.github.octaviusframework.driver.type

import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * A domain inside an array or a composite, where the server still names it: read as the type under it, as a column
 * over one is, and written through the shape of that type while going out declared as the domain.
 */
class DomainIntegrationTest : AbstractIntegrationTest() {

    enum class Allegiance { LOYAL, REBEL }

    data class Domicile(val street: String, val city: String)

    data class Embassy(val side: Allegiance, val home: Domicile)

    override val schema = """
        CREATE TYPE allegiance AS ENUM ('LOYAL', 'REBEL');
        CREATE DOMAIN sworn AS allegiance;
        CREATE DOMAIN oath AS sworn;
        CREATE DOMAIN dossier AS jsonb;
        CREATE TYPE domicile AS (street text, city text);
        CREATE DOMAIN seat AS domicile;
        CREATE DOMAIN cohorts AS int[];
        CREATE DOMAIN service AS int4range;

        CREATE TYPE envoy AS (side sworn, papers dossier, home seat);
        CREATE TYPE embassy AS (side sworn, home seat);
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { session ->
            session.typeManager.registerEnum<Allegiance>("allegiance")
            session.typeManager.registerAutoComposite<Domicile>("domicile")
            session.typeManager.registerAutoComposite<Embassy>("embassy")
        }
    }

    @Test
    fun `an enum under a domain reads as its class inside an array`() {
        openSession().use { s ->
            val sides = s.createNativeQuery("SELECT ARRAY['REBEL']::sworn[] AS sides").fetchRowStrict()
                .get<List<Allegiance>>("sides")

            assertEquals(listOf(Allegiance.REBEL), sides)
        }
    }

    @Test
    fun `a domain over a domain reads as the type at the bottom`() {
        openSession().use { s ->
            val oaths = s.createNativeQuery("SELECT ARRAY['LOYAL']::oath[] AS oaths").fetchRowStrict()
                .get<List<Allegiance>>("oaths")

            assertEquals(listOf(Allegiance.LOYAL), oaths)
        }
    }

    @Test
    fun `attributes over domains read as the types under them`() {
        openSession().use { s ->
            val envoy = s.createNativeQuery(
                """SELECT ROW('REBEL', '{"legatus": "Varus"}', ROW('Via Appia', 'Roma'))::envoy AS envoy"""
            ).fetchRowStrict().get<Map<String, Any?>>("envoy")

            assertEquals(Allegiance.REBEL, envoy["side"])
            val papers = envoy["papers"]
            assertTrue(papers is JsonObject, "papers came back as ${papers?.let { it::class }}")
            assertEquals("Varus", (papers as JsonObject)["legatus"]?.jsonPrimitive?.content)
            assertEquals(Domicile("Via Appia", "Roma"), envoy["home"])
        }
    }

    @Test
    fun `a composite under a domain reads as its registered class inside an array`() {
        openSession().use { s ->
            val seats = s.createNativeQuery("SELECT ARRAY[ROW('Via Appia', 'Roma')::seat] AS seats").fetchRowStrict()
                .get<List<Any>>("seats")

            assertEquals(listOf(Domicile("Via Appia", "Roma")), seats)
        }
    }

    @Test
    fun `a composite with attributes over domains is written`() {
        openSession().use { s ->
            val embassy = Embassy(Allegiance.LOYAL, Domicile("Via Sacra", "Roma"))

            val back = s.createNativeQuery("SELECT $1 AS embassy").fetchRowStrict(embassy.withPgType("embassy"))
                .get<Embassy>("embassy")

            assertEquals(embassy, back)
        }
    }

    @Test
    fun `a collection declared as a domain over an array goes out as the domain`() {
        openSession().use { s ->
            val type = s.createNativeQuery("SELECT pg_typeof($1)::text AS type")
                .fetchRowStrict(listOf(1, 2).withPgType("cohorts")).get<String>("type")

            assertEquals("cohorts", type)
        }
    }

    @Test
    fun `a range declared as a domain over a range type goes out as the domain`() {
        openSession().use { s ->
            val type = s.createNativeQuery("SELECT pg_typeof($1)::text AS type")
                .fetchRowStrict(rangeOf(9, 34).withPgType("service")).get<String>("type")

            assertEquals("service", type)
        }
    }

    @Test
    fun `a container built by hand goes out as the domain withPgType names`() {
        openSession().use { s ->
            val home = s.typeManager.containers.createComposite("domicile").apply {
                this["street"] = "Via Appia"
                this["city"] = "Roma"
            }

            val type = s.createNativeQuery("SELECT pg_typeof($1)::text AS type")
                .fetchRowStrict(home.withPgType("seat")).get<String>("type")

            assertEquals("seat", type)
        }
    }

    @Test
    fun `a composite declared as a domain over it goes out as the domain`() {
        openSession().use { s ->
            val type = s.createNativeQuery("SELECT pg_typeof($1)::text AS type")
                .fetchRowStrict(Domicile("Via Appia", "Roma").withPgType("seat")).get<String>("type")

            assertEquals("seat", type)
        }
    }
}
