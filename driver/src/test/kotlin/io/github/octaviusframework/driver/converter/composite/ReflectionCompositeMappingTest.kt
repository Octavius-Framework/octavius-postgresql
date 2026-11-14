package io.github.octaviusframework.driver.converter.composite

import io.github.octaviusframework.annotation.PgName
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * How a registered class lines up with the attributes of its composite: by name, with a [PgName] standing in for
 * the property's own where the two differ, and a private constructor property counting like any other.
 *
 * The written side is read back attribute by attribute on the server, since a round trip alone would pass a
 * mapping that is wrong the same way in both directions.
 */
class ReflectionCompositeMappingTest : AbstractIntegrationTest() {

    data class Domicile(val street: String, val city: String)

    data class Freedman(
        val id: Int,
        @PgName("full_name") val name: String,
        @PgName("home_address") val address: Domicile
    )

    data class Informer(val cognomen: String, private val source: String)

    override val schema = """
        CREATE TYPE domicile AS (street text, city text);
        CREATE TYPE freedman AS (id int, full_name text, home_address domicile);
        CREATE TYPE informer AS (cognomen text, source text);
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { s ->
            s.typeManager.registerAutoComposite<Domicile>("domicile")
            s.typeManager.registerAutoComposite<Freedman>("freedman")
            s.typeManager.registerAutoComposite<Informer>("informer")
        }
    }

    private val tiro = Freedman(15, "Marcus Tullius Tiro", Domicile("Via Sacra", "Roma"))

    @Test
    fun `a property under PgName reads from the attribute it names`() {
        val read = openSession().use {
            it.createNativeQuery("SELECT ROW(15, 'Marcus Tullius Tiro', ROW('Via Sacra', 'Roma')::domicile)::freedman")
                .fetchFieldStrict<Freedman>()
        }

        assertEquals(tiro, read)
    }

    @Test
    fun `a property under PgName is written to the attribute it names`() {
        val row = openSession().use {
            it.createNativeQuery("SELECT ($1).full_name AS name, (($1).home_address).city AS city, $1 AS back")
                .fetchRowStrict(tiro)
        }

        assertEquals("Marcus Tullius Tiro", row.get<String>("name"))
        assertEquals("Roma", row.get<String>("city"))
        assertEquals(tiro, row.get<Freedman>("back"))
    }

    @Test
    fun `a private constructor property is read and written like any other`() {
        val curius = Informer("Curius", "Fulvia")

        val row = openSession().use {
            it.createNativeQuery("SELECT ($1).source AS source, $1 AS back").fetchRowStrict(curius)
        }

        assertEquals("Fulvia", row.get<String>("source"))
        assertEquals(curius, row.get<Informer>("back"))
    }
}
