package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A row, a composite or a `ROW(...)` read as a map holds a `NULL` only where the map's value type takes one. */
class MapValueNullabilityTest : AbstractIntegrationTest() {

    override val schema = "CREATE TYPE census_entry AS (cognomen text, iugera int4)"

    private inline fun <reified T> read(sql: String): T =
        openSession().use { it.createNativeQuery(sql).fetchFieldStrict<T>() }

    private inline fun <reified T> refused(sql: String): MappingException =
        assertFailsWith<MappingException> { read<T>(sql) }

    @Test
    fun `a row with a NULL column is refused as a map of non-nullable values`() {
        val e = assertFailsWith<MappingException> {
            openSession().use {
                it.createNativeQuery("SELECT 7 AS legio, NULL::int4 AS cohors").fetchRowStrict().toObject<Map<String, Int>>()
            }
        }

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        assertEquals(listOf("cohors"), e.path)
    }

    @Test
    fun `a row with a NULL column is a null entry in a map of nullable values`() {
        val mapped = openSession().use {
            it.createNativeQuery("SELECT 7 AS legio, NULL::int4 AS cohors").fetchRowStrict().toObject<Map<String, Int?>>()
        }

        assertEquals(mapOf("legio" to 7, "cohors" to null), mapped)
    }

    @Test
    fun `a composite with a NULL attribute is refused as a map of non-nullable values`() {
        val e = refused<Map<String, Any>>("SELECT ROW('Cicero', NULL)::census_entry AS entry")

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        assertEquals(listOf("entry", "iugera"), e.path.asReversed())
    }

    @Test
    fun `a composite with a NULL attribute is a null entry in a map of nullable values`() {
        val mapped = read<Map<String, Any?>>("SELECT ROW('Cicero', NULL)::census_entry AS entry")

        assertEquals(mapOf("cognomen" to "Cicero", "iugera" to null), mapped)
    }

    @Test
    fun `a record with a NULL value is refused as a map of non-nullable values`() {
        val e = refused<Map<String, String>>("SELECT ROW('status', 'active', 'province', NULL::text) AS r")

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, e.reason)
        assertEquals(listOf("r", "province"), e.path.asReversed())
    }
}
