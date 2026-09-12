package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The end of the record-as-map reading the unit test cannot reach: that the key type asked for is resolved
 * against the OID PostgreSQL actually sends for a `ROW(...)` field, rather than the one a hand-built
 * [io.github.octaviusframework.driver.container.PgRecord] was given.
 *
 * What the `PgRecord` section of `docs/driver/composites-reflection.md` says happens, held against a server:
 * whether a bare literal in `ROW(1, 'primus')` arrives as `int4` and `text` or as `unknown` is what decides
 * whether a key type can be named for it at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordMapKeyIntegrationTest {

    data class RmkTribute(val amount: Int, val currency: String)

    private fun session() = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")

    @BeforeAll
    fun setup() {
        session().use { session ->
            session.createNativeQuery("DROP TYPE IF EXISTS rmk_tribute CASCADE").execute()
            session.createNativeQuery("CREATE TYPE rmk_tribute AS (amount int, currency text)").execute()
        }
    }

    @AfterAll
    fun teardown() {
        session().use { session ->
            session.createNativeQuery("DROP TYPE IF EXISTS rmk_tribute CASCADE").execute()
        }
    }

    @Test
    fun `text keys read as the documented shape`() {
        session().use { session ->
            val r: Map<String, Any?> = session
                .createNativeQuery("SELECT ROW('status', 'active', 'province', 7) AS r")
                .fetchRowStrict().get("r")

            assertEquals(mapOf<String, Any?>("status" to "active", "province" to 7), r)
        }
    }

    @Test
    fun `int4 keys read as Int where the map says so`() {
        session().use { session ->
            val r: Map<Int, String> = session
                .createNativeQuery("SELECT ROW(1, 'primus', 2, 'secundus') AS r")
                .fetchRowStrict().get("r")

            assertEquals(mapOf(1 to "primus", 2 to "secundus"), r)
        }
    }

    @Test
    fun `a composite key is the class it is registered as`() {
        session().use { session ->
            session.reloadTypes()
            session.typeManager.registerAutoComposite<RmkTribute>("rmk_tribute")

            val r: Map<RmkTribute, Int> = session
                .createNativeQuery("SELECT ROW(ROW(40, 'denarius')::rmk_tribute, 7) AS r")
                .fetchRowStrict().get("r")

            assertEquals(7, r[RmkTribute(40, "denarius")])
        }
    }

    @Test
    fun `keys of different types stay different keys`() {
        session().use { session ->
            val r: Map<Any, Any?> = session
                .createNativeQuery("SELECT ROW(1, 'by number', '1'::text, 'by name') AS r")
                .fetchRowStrict().get("r")

            assertEquals(2, r.size)
            assertEquals("by number", r[1])
            assertEquals("by name", r["1"])
        }
    }

    @Test
    fun `an int4 key under a String-keyed map is refused rather than stringified`() {
        session().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW(1, 'primus') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }

    @Test
    fun `a NULL key is refused`() {
        session().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW(NULL::text, 'active') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }

    @Test
    fun `a duplicate key is refused`() {
        session().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW('status', 'active', 'status', 'pending') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }
}
