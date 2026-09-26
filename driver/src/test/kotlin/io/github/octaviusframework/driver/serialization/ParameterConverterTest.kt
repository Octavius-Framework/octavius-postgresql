package io.github.octaviusframework.driver.serialization

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class ParameterConverterTest : AbstractIntegrationTest() {

    private lateinit var session: OctaviusSession

    data class Domus(val city: String, val street: String)
    data class Citizen(val id: Int, val name: String, val domus: Domus, val titles: List<String>)

    override val schema = """
        CREATE TYPE domus AS (city text, street text);
        CREATE TYPE citizen AS (id int, name text, domus domus, titles text[]);
    """.trimIndent()

    @BeforeAll
    fun setup() {
        session = openSession()
        session.typeManager.registerAutoComposite<Domus>("domus")
        session.typeManager.registerAutoComposite<Citizen>("citizen")
    }

    @AfterAll
    fun teardown() {
        session.close()
    }

    @Test
    fun testDataClassToCompositeConversion() {
        val domus = Domus("Roma", "Via Sacra")
        val cicero = Citizen(42, "Marcus Tullius Cicero", domus, listOf("consul", "pater patriae"))

        val returned = session.createNativeQuery("SELECT ($1).*")
            .fetchObjectStrict<Citizen>(cicero)
        assertEquals(42, returned.id)
        assertEquals("Marcus Tullius Cicero", returned.name)
        assertEquals("Roma", returned.domus.city)
        assertEquals("Via Sacra", returned.domus.street)
        assertEquals(2, returned.titles.size)
        assertEquals("consul", returned.titles[0])
        assertEquals("pater patriae", returned.titles[1])
    }

    @Test
    fun testSimpleListConversion() {
        val list = listOf("Romulus", "Numa", "Tullus")
        val returnedArray = session.createNativeQuery("SELECT $1 as res")
            .fetchRows(list)
            .first()
            .get<PgArray>("res")
        assertNotNull(returnedArray)
        assertEquals("Romulus", returnedArray.get<String>(0))
        assertEquals("Numa", returnedArray.get<String>(1))
        assertEquals("Tullus", returnedArray.get<String>(2))
    }
}

