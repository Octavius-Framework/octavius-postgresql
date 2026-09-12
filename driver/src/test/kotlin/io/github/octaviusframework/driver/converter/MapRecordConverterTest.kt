package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.container.PgRecord
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverterRegistry
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.converter.result.record.MapRecordConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.registry.TypeRegistry
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers [MapRecordConverter]: an anonymous `ROW(...)` read as a map, with both halves of each pair going
 * through the converter chain against the `Map` type arguments asked for.
 *
 * The registries are built by hand rather than by opening a session, because what is under test is which type
 * each half of a pair is converted against - the key having been stringified regardless before this.
 */
class MapRecordConverterTest {

    data class Tribute(val amount: Int, val currency: String)

    private val text = PgType.Base(1, "text", "public")
    private val int4 = PgType.Base(2, "int4", "public")
    private val tribute = PgType.Composite(3, "tribute", "public", linkedMapOf("amount" to 2, "currency" to 1))

    private val typeRegistry = TypeRegistry().apply {
        updateTypes(mapOf(1 to text, 2 to int4, 3 to tribute, 2249 to PgType.Record))
    }

    private val typeManager = TypeManager(typeRegistry).apply {
        registerAutoComposite<Tribute>("tribute", "public")
    }

    private val mapper = ResultMapper(
        ResultConverterRegistry().apply {
            addConverter(MapRecordConverter)
            addConverter(ReflectionCompositeConverter)
        },
        typeManager
    )

    /** `ROW(...)` as the codec hands it over: the field OIDs alongside the decoded values. */
    private fun record(vararg fields: Pair<Int, Any?>) =
        PgRecord(PgType.Record, fields.map { it.first }.toIntArray(), fields.map { it.second }.toTypedArray())

    private fun aTribute() = PgComposite(tribute, arrayOf(40, "denarius"))

    // --- What the key is converted against ------------------------------------------------------------

    @Test
    fun `text keys under the documented shape read as before`() {
        val r = record(1 to "status", 1 to "active", 1 to "province", 2 to 7)

        val mapped: Map<String, Any?> = mapper.deserialize(r, typeOf<Map<String, Any?>>(), PgType.Record)

        assertEquals(mapOf<String, Any?>("status" to "active", "province" to 7), mapped)
    }

    @Test
    fun `an Int key is an Int where the map says so`() {
        val r = record(2 to 1, 1 to "primus", 2 to 2, 1 to "secundus")

        val mapped: Map<Int, String> = mapper.deserialize(r, typeOf<Map<Int, String>>(), PgType.Record)

        assertEquals(mapOf(1 to "primus", 2 to "secundus"), mapped)
    }

    @Test
    fun `a composite key is the class it is registered as`() {
        val r = record(3 to aTribute(), 2 to 7)

        val mapped: Map<Tribute, Int> = mapper.deserialize(r, typeOf<Map<Tribute, Int>>(), PgType.Record)

        // The point of naming a data class for it: the key is reachable by equality rather than by identity,
        // which a raw container would be.
        assertEquals(7, mapped[Tribute(40, "denarius")])
    }

    @Test
    fun `a composite key is the registered class under Any too`() {
        val r = record(3 to aTribute(), 2 to 7)

        // Nothing has to be said about the key for a registered composite to arrive as its class: `Any`
        // reaches the reflective converter the same way naming the class does.
        val mapped: Map<Any, Any?> = mapper.deserialize(r, typeOf<Map<Any, Any?>>(), PgType.Record)

        assertEquals(7, mapped[Tribute(40, "denarius")])
    }

    @Test
    fun `asked as Any each half is what the converter chain makes of it`() {
        val r = record(2 to 1, 1 to "primus")

        val mapped = mapper.deserialize<Any>(r, typeOf<Any>(), PgType.Record) as Map<*, *>

        // Not the string "1", which is what stringifying the key used to make of it.
        assertEquals(mapOf(1 to "primus"), mapped)
        assertIs<Int>(mapped.keys.single())
    }

    @Test
    fun `keys of different types stay different keys`() {
        // The collision stringifying produced: an int4 1 and a text '1' are two keys in SQL and were one in
        // the map, the second field silently replacing the first.
        val r = record(2 to 1, 1 to "by number", 1 to "1", 1 to "by name")

        val mapped: Map<Any, Any?> = mapper.deserialize(r, typeOf<Map<Any, Any?>>(), PgType.Record)

        assertEquals(2, mapped.size)
        assertEquals("by number", mapped[1])
        assertEquals("by name", mapped["1"])
    }

    @Test
    fun `a key that converts to none of what was asked for fails where it is met`() {
        val r = record(2 to 1, 1 to "primus")

        val e = assertFailsWith<MappingException> {
            mapper.deserialize<Map<String, Any?>>(r, typeOf<Map<String, Any?>>(), PgType.Record)
        }

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, e.reason)
        // The position is what names a record's fields, there being no names on the wire to use.
        assertEquals(listOf("[0]"), e.path)
    }

    // --- What the values still do ---------------------------------------------------------------------

    @Test
    fun `values go through the full chain, nested records included`() {
        val r = record(
            1 to "payload", 3 to aTribute(),
            1 to "inner", 2249 to record(1 to "depth", 2 to 2)
        )

        val mapped: Map<String, Any?> = mapper.deserialize(r, typeOf<Map<String, Any?>>(), PgType.Record)

        assertEquals(Tribute(40, "denarius"), mapped["payload"])
        assertEquals(mapOf("depth" to 2), mapped["inner"])
    }

    @Test
    fun `a null value is a null entry rather than a failure`() {
        val r = record(1 to "province", 1 to null)

        val mapped: Map<String, Any?> = mapper.deserialize(r, typeOf<Map<String, Any?>>(), PgType.Record)

        assertEquals(setOf("province"), mapped.keys)
        assertNull(mapped["province"])
    }

    // --- The three refusals --------------------------------------------------------------------------

    @Test
    fun `an odd number of fields is refused`() {
        val r = record(1 to "status", 1 to "active", 1 to "orphan")

        val e = assertFailsWith<MappingException> {
            mapper.deserialize<Map<String, Any?>>(r, typeOf<Map<String, Any?>>(), PgType.Record)
        }

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
    }

    @Test
    fun `a NULL key is refused rather than read as the string null`() {
        val r = record(1 to null, 1 to "active")

        val e = assertFailsWith<MappingException> {
            mapper.deserialize<Map<String, Any?>>(r, typeOf<Map<String, Any?>>(), PgType.Record)
        }

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
        assertEquals(listOf("[0]"), e.path)
    }

    @Test
    fun `a duplicate key is refused rather than replacing the field already read`() {
        val r = record(1 to "status", 1 to "active", 1 to "status", 1 to "pending")

        val e = assertFailsWith<MappingException> {
            mapper.deserialize<Map<String, Any?>>(r, typeOf<Map<String, Any?>>(), PgType.Record)
        }

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)
        // The second key is the one at fault, not the first.
        assertEquals(listOf("[2]"), e.path)
    }
}
