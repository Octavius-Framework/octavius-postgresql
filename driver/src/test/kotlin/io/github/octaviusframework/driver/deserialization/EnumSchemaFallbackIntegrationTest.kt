package io.github.octaviusframework.driver.deserialization

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * One enum type name registered once without a schema and once with one: the schema named gets its own enum, and
 * every other schema falls back to the one registered without - whichever was registered first.
 */
class EnumSchemaFallbackIntegrationTest : AbstractIntegrationTest() {

    enum class Rank { Legatus, Tribunus }
    enum class ProvincialRank { Legatus, Tribunus }

    override val schema = """
        CREATE SCHEMA provincia;
        CREATE SCHEMA italia;
        CREATE TYPE rank AS ENUM ('LEGATUS', 'TRIBUNUS');
        CREATE TYPE provincia.rank AS ENUM ('LEGATUS', 'TRIBUNUS');
        CREATE TYPE italia.rank AS ENUM ('LEGATUS', 'TRIBUNUS');
    """.trimIndent()

    @BeforeAll
    fun register() {
        // The schema-qualified one first, so that the bare one is the newer converter and would be asked first.
        openSession().use { session ->
            session.typeManager.registerEnum<ProvincialRank>("rank", "provincia")
            session.typeManager.registerEnum<Rank>("rank")
        }
    }

    @Test
    fun `read as Any, the schema named takes its own enum and every other the shared one`() {
        openSession().use { session ->
            val row = session.createNativeQuery(
                """
                SELECT 'LEGATUS'::provincia.rank AS provincial,
                       'LEGATUS'::rank AS public_one,
                       'TRIBUNUS'::italia.rank AS other
                """.trimIndent()
            ).fetchRowStrict()

            assertEquals(ProvincialRank.Legatus, row.get<Any>("provincial"))
            assertEquals(Rank.Legatus, row.get<Any>("public_one"))
            assertEquals(Rank.Tribunus, row.get<Any>("other"))
        }
    }

    @Test
    fun `each enum goes out as the type it was registered under`() {
        openSession().use { session ->
            val row = session
                .createNativeQuery("SELECT pg_typeof($1)::text AS provincial, pg_typeof($2)::text AS shared")
                .fetchRowStrict(ProvincialRank.Legatus, Rank.Legatus)

            assertEquals("provincia.rank", row.get<String>("provincial"))
            assertEquals("rank", row.get<String>("shared"))
        }
    }
}
