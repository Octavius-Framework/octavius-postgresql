package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The live search path, as PostgreSQL 18 reports it in `ParameterStatus` - and the type names resolved against it.
 *
 * Every test moves the path by talking to the server and never through the driver, so what `searchPath` answers
 * is what the server announced. Two provinces each define a `tribute` of their own shape, which is what makes an
 * unqualified name mean one thing or the other depending on the path; a third type lives in one of them only, and
 * a fourth in both, for the two rules that apply to a name the path does not reach.
 */
class SearchPathIntegrationTest : AbstractIntegrationTest() {

    override val schema = """
        CREATE SCHEMA gallia;
        CREATE SCHEMA hispania;
        CREATE TYPE gallia.tribute AS (amount int);
        CREATE TYPE hispania.tribute AS (amount int, currency text);
        CREATE TYPE hispania.mine AS (ore text);
        CREATE TYPE gallia.oppidum AS (name text);
        CREATE TYPE hispania.oppidum AS (name text);
    """.trimIndent()

    @Test
    fun `SET moves the path, and the session knows without asking`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia, public").execute()
            assertEquals(listOf("gallia", "public"), session.searchPath)

            session.createNativeQuery("SET search_path TO hispania").execute()
            assertEquals(listOf("hispania"), session.searchPath)
        }
    }

    @Test
    fun `a set_config inside a query moves it as well`() {
        openSession().use { session ->
            session.createNativeQuery("SELECT set_config('search_path', 'hispania, public', false)").fetchField<String>()

            assertEquals(listOf("hispania", "public"), session.searchPath)
        }
    }

    @Test
    fun `SET LOCAL lasts until the transaction ends, whichever way it ends`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia").execute()

            session.createNativeQuery("BEGIN").execute()
            session.createNativeQuery("SET LOCAL search_path TO hispania").execute()
            assertEquals(listOf("hispania"), session.searchPath)
            session.createNativeQuery("ROLLBACK").execute()
            assertEquals(listOf("gallia"), session.searchPath)

            session.createNativeQuery("BEGIN").execute()
            session.createNativeQuery("SET LOCAL search_path TO hispania").execute()
            session.createNativeQuery("COMMIT").execute()
            assertEquals(listOf("gallia"), session.searchPath)
        }
    }

    @Test
    fun `an unqualified type name follows the path from one statement to the next`() {
        openSession().use { session ->
            val types = session.typeManager

            session.createNativeQuery("SET search_path TO gallia").execute()
            assertEquals(types.resolveOid("tribute", "gallia"), types.resolveOid("tribute"))
            assertEquals(listOf("amount"), types.containers.createComposite("tribute").type.attributes.keys.toList())

            session.createNativeQuery("SET search_path TO hispania").execute()
            assertEquals(types.resolveOid("tribute", "hispania"), types.resolveOid("tribute"))
            assertEquals(
                listOf("amount", "currency"),
                types.containers.createComposite("tribute").type.attributes.keys.toList()
            )
        }
    }

    @Test
    fun `a value built by an unqualified name goes out as the type the path found`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO hispania").execute()
            val tribute = session.typeManager.containers.createComposite("tribute")
            tribute["amount"] = 40
            tribute["currency"] = "denarius"

            val sent = session.createNativeQuery("SELECT pg_typeof($1)::oid").fetchFieldStrict<Int>(tribute)

            assertEquals(session.typeManager.resolveOid("tribute", "hispania"), sent)
        }
    }

    @Test
    fun `a name the path does not reach resolves where it is the only one of its name`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia").execute()

            assertEquals(session.typeManager.resolveOid("mine", "hispania"), session.typeManager.resolveOid("mine"))
        }
    }

    @Test
    fun `a name the path does not reach and two schemas define is refused rather than guessed`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO public").execute()

            assertFailsWith<TypeException> { session.typeManager.resolveOid("oppidum") }
        }
    }
}
