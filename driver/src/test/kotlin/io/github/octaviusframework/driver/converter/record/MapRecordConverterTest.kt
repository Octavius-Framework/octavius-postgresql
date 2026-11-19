package io.github.octaviusframework.driver.converter.record

import io.github.octaviusframework.driver.converter.result.record.MapRecordConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers [MapRecordConverter]: an anonymous `ROW(...)` of keys and values read as a map, with both halves of each
 * pair going through the converter chain against the `Map` type arguments asked for.
 *
 * Which type a key is converted against is the OID PostgreSQL sends for its field: a bare literal in
 * `ROW(1, 'primus')` arrives as `int4` and `text`, which is what lets a key type be named for it at all.
 */
class MapRecordConverterTest : AbstractIntegrationTest() {

    data class Tribute(val amount: Int, val currency: String)

    override val schema = "CREATE TYPE tribute AS (amount int, currency text)"

    @BeforeAll
    fun register() {
        openSession().use { it.typeManager.registerAutoComposite<Tribute>("tribute") }
    }

    private inline fun <reified T> read(row: String): T =
        openSession().use { it.createNativeQuery("SELECT $row AS r").fetchFieldStrict<T>() }

    private inline fun <reified T> refused(row: String): MappingException =
        assertFailsWith<MappingException> { read<T>(row) }

    //----------------------------------------WHAT THE KEY IS CONVERTED AGAINST-----------------------------------------

    @Test
    fun `text keys read as the documented shape`() {
        val mapped = read<Map<String, Any?>>("ROW('status', 'active', 'province', 7)")

        assertEquals(mapOf<String, Any?>("status" to "active", "province" to 7), mapped)
    }

    @Test
    fun `an int4 key is an Int where the map says so`() {
        val mapped = read<Map<Int, String>>("ROW(1, 'primus', 2, 'secundus')")

        assertEquals(mapOf(1 to "primus", 2 to "secundus"), mapped)
    }

    @Test
    fun `a composite key is the class it is registered as`() {
        val mapped = read<Map<Tribute, Int>>("ROW(ROW(40, 'denarius')::tribute, 7)")

        // The point of naming a data class for it: the key is reachable by equality rather than by identity,
        // which a raw container would be.
        assertEquals(7, mapped[Tribute(40, "denarius")])
    }

    @Test
    fun `a composite key is the registered class under Any too`() {
        // Nothing has to be said about the key for a registered composite to arrive as its class: `Any` reaches
        // the reflective converter the same way naming the class does.
        val mapped = read<Map<Any, Any?>>("ROW(ROW(40, 'denarius')::tribute, 7)")

        assertEquals(7, mapped[Tribute(40, "denarius")])
    }

    @Test
    fun `asked as Any each half is what the converter chain makes of it`() {
        val mapped = read<Any>("ROW(1, 'primus')") as Map<*, *>

        assertEquals(mapOf(1 to "primus"), mapped)
        assertIs<Int>(mapped.keys.single())
    }

    @Test
    fun `keys of different types stay different keys`() {
        // An int4 1 and a text '1' are two keys in SQL, so they are two in the map as well.
        val mapped = read<Map<Any, Any?>>("ROW(1, 'by number', '1'::text, 'by name')")

        assertEquals(2, mapped.size)
        assertEquals("by number", mapped[1])
        assertEquals("by name", mapped["1"])
    }

    @Test
    fun `a key that converts to none of what was asked for fails where it is met`() {
        val e = refused<Map<String, Any?>>("ROW(1, 'primus')")

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, e.reason)
        // The position is what names a record's fields, there being no names on the wire to use.
        assertEquals(listOf("r", "[0]"), e.path.asReversed())
    }

    //--------------------------------------------WHAT THE VALUES GO THROUGH--------------------------------------------

    @Test
    fun `values go through the full chain, nested records included`() {
        val mapped = read<Map<String, Any?>>(
            "ROW('payload', ROW(40, 'denarius')::tribute, 'inner', ROW('depth', 2))"
        )

        assertEquals(Tribute(40, "denarius"), mapped["payload"])
        assertEquals(mapOf("depth" to 2), mapped["inner"])
    }

    @Test
    fun `a null value is a null entry rather than a failure`() {
        val mapped = read<Map<String, Any?>>("ROW('province', NULL::text)")

        assertEquals(setOf("province"), mapped.keys)
        assertNull(mapped["province"])
    }

    //------------------------------------------------THE THREE REFUSALS------------------------------------------------

    @Test
    fun `an odd number of fields is refused`() {
        val e = refused<Map<String, Any?>>("ROW('status', 'active', 'orphan')")

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
    }

    @Test
    fun `a NULL key is refused rather than read as the string null`() {
        val e = refused<Map<String, Any?>>("ROW(NULL::text, 'active')")

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
        assertEquals(listOf("r", "[0]"), e.path.asReversed())
    }

    @Test
    fun `a duplicate key is refused rather than replacing the field already read`() {
        val e = refused<Map<String, Any?>>("ROW('status', 'active', 'status', 'pending')")

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
        // The second key is the one at fault, not the first.
        assertEquals(listOf("r", "[2]"), e.path.asReversed())
    }
}
