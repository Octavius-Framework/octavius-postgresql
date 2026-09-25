package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
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
class RecordMapKeyIntegrationTest : AbstractIntegrationTest() {

    data class RmkTribute(val amount: Int, val currency: String)

    override val schema = "CREATE TYPE rmk_tribute AS (amount int, currency text)"

    @Test
    fun `text keys read as the documented shape`() {
        openSession().use { session ->
            val r: Map<String, Any?> = session
                .createNativeQuery("SELECT ROW('status', 'active', 'province', 7) AS r")
                .fetchRowStrict().get("r")

            assertEquals(mapOf<String, Any?>("status" to "active", "province" to 7), r)
        }
    }

    @Test
    fun `int4 keys read as Int where the map says so`() {
        openSession().use { session ->
            val r: Map<Int, String> = session
                .createNativeQuery("SELECT ROW(1, 'primus', 2, 'secundus') AS r")
                .fetchRowStrict().get("r")

            assertEquals(mapOf(1 to "primus", 2 to "secundus"), r)
        }
    }

    @Test
    fun `a composite key is the class it is registered as`() {
        openSession().use { session ->
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
        openSession().use { session ->
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
        openSession().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW(1, 'primus') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }

    @Test
    fun `a NULL key is refused`() {
        openSession().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW(NULL::text, 'active') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }

    @Test
    fun `a duplicate key is refused`() {
        openSession().use { session ->
            assertFailsWith<MappingException> {
                session.createNativeQuery("SELECT ROW('status', 'active', 'status', 'pending') AS r")
                    .fetchRowStrict().get<Map<String, Any?>>("r")
            }
        }
    }
}
