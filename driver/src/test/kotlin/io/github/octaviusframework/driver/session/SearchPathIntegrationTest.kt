package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The live search path, as PostgreSQL 18 reports it in `ParameterStatus` - and the type names resolved against it.
 *
 * Every test moves the path by talking to the server and never through the driver, so what `searchPath` answers
 * is what the server announced. Two provinces each define a `tribute` of their own shape, which is what makes an
 * unqualified name mean one thing or the other depending on the path; a third type lives in one of them only, and
 * a fourth in both, for what the refusal of a name the path does not reach says about either.
 *
 * The rest is where the reported setting and the path the server searches part ways. The test user has a schema
 * of its own for `$user` to stand for, with a `census` that `public` and a province have too; a province has a
 * `money`, a name `pg_catalog` has already; and a province's name is longer than the server keeps, with a
 * `colonia` that another province has too.
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
        CREATE SCHEMA ${TestDatabase.USER};
        CREATE TYPE ${TestDatabase.USER}.census AS (heads int);
        CREATE TYPE public.census AS (heads int, province text);
        CREATE TYPE gallia.census AS (heads int, tribe text);
        CREATE TYPE gallia.money AS (denarii int);
        CREATE SCHEMA $LONG_PROVINCE;
        CREATE TYPE $LONG_PROVINCE.colonia AS (name text);
        CREATE TYPE hispania.colonia AS (name text, conventus text);
    """.trimIndent()

    /** What the server resolves [typeName] to on this session's path, for the driver's answer to agree with. */
    private fun OctaviusSession.serverResolves(typeName: String): Int =
        createNativeQuery("SELECT to_regtype($1)::oid").fetchFieldStrict<Int>(typeName)

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
    fun `a name the path does not reach is refused even where one schema has it, and the refusal names it`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia").execute()

            val refused = assertFailsWith<TypeException> { session.typeManager.resolveOid("mine") }

            assertEquals(TypeExceptionReason.TYPE_NOT_FOUND, refused.reason)
            assertEquals(
                "Type 'mine' is not on the search path [pg_catalog, gallia], but schema 'hispania' has it. " +
                    "Name the schema, or put it on the search path.",
                refused.details
            )
        }
    }

    @Test
    fun `a name the path does not reach and two schemas define is refused, naming both`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO public").execute()

            val refused = assertFailsWith<TypeException> { session.typeManager.resolveOid("oppidum") }

            assertTrue("schemas 'gallia', 'hispania' have it" in refused.details!!, refused.details)
        }
    }

    @Test
    fun `a reload takes in no temporary type, another session's or this one's`() {
        openSession().use { other ->
            other.createNativeQuery("CREATE TYPE pg_temp.mine AS (ore text); CREATE TYPE pg_temp.castra AS (name text)")
                .execute()
            val otherTemp = other.createNativeQuery("SELECT pg_my_temp_schema()::regnamespace::text").fetchField<String>()!!

            openSession().use { session ->
                session.createNativeQuery("CREATE TYPE pg_temp.vallum AS (height int)").execute()
                val ownTemp = session.createNativeQuery("SELECT pg_my_temp_schema()::regnamespace::text").fetchField<String>()!!
                session.reloadTypes()
                session.createNativeQuery("SET search_path TO gallia").execute()

                assertFailsWith<TypeException> { session.typeManager.resolveOid("castra", otherTemp) }
                assertFailsWith<TypeException> { session.typeManager.resolveOid("vallum", ownTemp) }
                // Nor does a name another session's temporary schema shares turn up among the schemas that have it.
                val shared = assertFailsWith<TypeException> { session.typeManager.resolveOid("mine") }
                assertTrue("but schema 'hispania' has it" in shared.details!!, shared.details)
            }
        }
    }

    @Test
    fun `"$user" is the session user's own schema, ahead of public`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO \"\$user\", public").execute()
            val own = session.typeManager.resolveOid("census", TestDatabase.USER)

            assertEquals(own, session.serverResolves("census"))
            assertEquals(own, session.typeManager.resolveOid("census"))
        }
    }

    @Test
    fun `with a schema for each user, "$user" finds this user's`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO \"\$user\"").execute()
            val own = session.typeManager.resolveOid("census", TestDatabase.USER)

            assertEquals(own, session.serverResolves("census"))
            assertEquals(own, session.typeManager.resolveOid("census"))
        }
    }

    @Test
    fun `pg_catalog is searched first where the path does not list it`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia").execute()
            val builtIn = session.typeManager.resolveOid("money", "pg_catalog")

            assertEquals(builtIn, session.serverResolves("money"))
            assertEquals(builtIn, session.typeManager.resolveOid("money"))
        }
    }

    @Test
    fun `pg_catalog listed after a schema is searched after it`() {
        openSession().use { session ->
            session.createNativeQuery("SET search_path TO gallia, pg_catalog").execute()
            val provincial = session.typeManager.resolveOid("money", "gallia")

            assertEquals(provincial, session.serverResolves("money"))
            assertEquals(provincial, session.typeManager.resolveOid("money"))
        }
    }

    @Test
    fun `a path set_config stores as written is read with unquoted names in lower case`() {
        openSession().use { session ->
            session.createNativeQuery("SELECT set_config('search_path', 'Hispania, \"Gallia\"', false)")
                .fetchField<String>()

            assertEquals(listOf("hispania", "Gallia"), session.searchPath)
            assertEquals(session.typeManager.resolveOid("tribute", "hispania"), session.typeManager.resolveOid("tribute"))
        }
    }

    @Test
    fun `a path given at startup is read the same way`() {
        openSession { additionalProperties["search_path"] = "Hispania" }.use { session ->
            assertEquals(listOf("hispania"), session.searchPath)
            assertEquals(session.typeManager.resolveOid("tribute", "hispania"), session.typeManager.resolveOid("tribute"))
        }
    }

    @Test
    fun `a name longer than the server keeps is cut where the server cuts it`() {
        openSession().use { session ->
            session.createNativeQuery("SELECT set_config('search_path', '$LONG_PROVINCE', false)").fetchField<String>()
            val kept = LONG_PROVINCE.take(63)
            val colonia = session.typeManager.resolveOid("colonia", kept)

            assertEquals(listOf(kept), session.searchPath)
            assertEquals(colonia, session.serverResolves("colonia"))
            assertEquals(colonia, session.typeManager.resolveOid("colonia"))
        }
    }

    private companion object {
        /** 65 characters, which the server cuts to 63 when the schema is created and when the path is read. */
        const val LONG_PROVINCE = "provincia_gallia_narbonensis_quondam_gallia_transalpina_appellata"
    }
}
