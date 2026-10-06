package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * One type name registered once without a schema and once with one: the schema named gets its own class, and
 * every other schema falls back to the one registered without.
 */
class CompositeSchemaFallbackIntegrationTest : AbstractIntegrationTest() {

    data class Address(val city: String, val street: String)
    data class ProvincialAddress(val city: String, val street: String)

    override val schema = """
        CREATE SCHEMA provincia;
        CREATE SCHEMA italia;
        CREATE TYPE address AS (city text, street text);
        CREATE TYPE provincia.address AS (city text, street text);
        CREATE TYPE italia.address AS (city text, street text);
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<Address>("address")
            session.typeManager.registerAutoComposite<ProvincialAddress>("address", "provincia")
        }
    }

    @Test
    fun `read as Any, the schema named takes its own class and every other the shared one`() {
        openSession().use { session ->
            val row = session.createNativeQuery(
                """
                SELECT ROW('Londinium', 'Watling Street')::provincia.address AS provincial,
                       ROW('Roma', 'Via Sacra')::address AS public_one,
                       ROW('Capua', 'Via Appia')::italia.address AS other
                """.trimIndent()
            ).fetchRowStrict()

            assertEquals(ProvincialAddress("Londinium", "Watling Street"), row.get<Any>("provincial"))
            assertEquals(Address("Roma", "Via Sacra"), row.get<Any>("public_one"))
            assertEquals(Address("Capua", "Via Appia"), row.get<Any>("other"))
        }
    }

    @Test
    fun `each class goes out as the type it was registered under`() {
        openSession().use { session ->
            val row = session
                .createNativeQuery("SELECT pg_typeof($1)::text AS provincial, pg_typeof($2)::text AS shared")
                .fetchRowStrict(ProvincialAddress("Londinium", "Watling Street"), Address("Roma", "Via Sacra"))

            assertEquals("provincia.address", row.get<String>("provincial"))
            assertEquals("address", row.get<String>("shared"))
        }
    }
}
