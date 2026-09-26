package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Every dimension of a PostgreSQL array is its own level on the Kotlin side, and each level can be any shape the
 * driver maps an array to - a collection, an `Array<T>` or a primitive array - in both directions.
 */
class NestedArrayIntegrationTest : AbstractIntegrationTest() {

    enum class Allegiance { Loyal, Rebel }
    data class Domicile(val street: String, val city: String)

    private lateinit var session: OctaviusSession

    override val schema = """
        CREATE TYPE allegiance AS ENUM ('LOYAL', 'REBEL');
        CREATE TYPE domicile AS (street text, city text);
    """.trimIndent()

    @BeforeAll
    fun setup() {
        session = openSession()
        session.typeManager.registerEnum<Allegiance>("allegiance")
        session.typeManager.registerAutoComposite<Domicile>("domicile")
    }

    @AfterAll
    fun teardown() {
        session.close()
    }

    private inline fun <reified T> read(sql: String): T = session.createNativeQuery(sql).fetchRowStrict().get(0)

    private inline fun <reified T> roundTrip(value: Any): T =
        session.createNativeQuery("SELECT $1").fetchRowStrict(value).get(0)

    private fun declaredType(value: Any): String =
        session.createNativeQuery("SELECT pg_typeof($1)::text").fetchRowStrict(value).get(0)

    @Test
    fun `a two-dimensional array reads as a list of primitive arrays`() {
        val cohorts: List<IntArray> = read("SELECT ARRAY[[1, 2, 3], [4, 5, 6]]")

        assertEquals(2, cohorts.size)
        assertArrayEquals(intArrayOf(1, 2, 3), cohorts[0])
        assertArrayEquals(intArrayOf(4, 5, 6), cohorts[1])
    }

    @Test
    fun `a three-dimensional array reads as a list of arrays of primitive arrays`() {
        val legions: List<Array<IntArray>> = read("SELECT ARRAY[[[1, 2], [3, 4]], [[5, 6], [7, 8]]]")

        assertEquals(2, legions.size)
        assertEquals(2, legions[1].size)
        assertArrayEquals(intArrayOf(7, 8), legions[1][1])
    }

    @Test
    fun `an array reads as Array of T, nulls and enums included`() {
        assertArrayEquals(arrayOf("Capua", "Ariminum"), read<Array<String>>("SELECT ARRAY['Capua', 'Ariminum']"))
        assertArrayEquals(arrayOf(1, null, 3), read<Array<Int?>>("SELECT ARRAY[1, NULL, 3]"))
        assertArrayEquals(
            arrayOf(Allegiance.Rebel, Allegiance.Loyal),
            read<Array<Allegiance>>("SELECT ARRAY['REBEL', 'LOYAL']::allegiance[]")
        )
        assertEquals(0, read<Array<String>>("SELECT '{}'::text[]").size)
    }

    @Test
    fun `an Array of lists takes the inner dimension as lists`() {
        val grid: Array<List<Int>> = read("SELECT ARRAY[[1, 2], [3, 4]]")

        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), grid.toList())
    }

    @Test
    fun `a Kotlin type with fewer levels than the array fails with the index in the path`() {
        val ex = assertThrows<MappingException> { read<List<Int>>("SELECT ARRAY[[1, 2], [3, 4]]") }

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, ex.reason)
        assertTrue(ex.path.contains("[0]"), "expected '[0]' in path, got ${ex.path}")
    }

    @Test
    fun `a primitive array refuses an array of more than one dimension`() {
        val ex = assertThrows<MappingException> { read<IntArray>("SELECT ARRAY[[1, 2], [3, 4]]") }

        assertEquals(MappingExceptionReason.CONVERSION_ERROR, ex.reason)
    }

    @Test
    fun `a NULL element fails a primitive array with its index, as it fails a List of Int`() {
        val ex = assertThrows<MappingException> { read<IntArray>("SELECT ARRAY[1, NULL, 3]") }

        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, ex.reason)
        assertTrue(ex.path.contains("[1]"), "expected '[1]' in path, got ${ex.path}")
    }

    @Test
    fun `primitive arrays nested in a list or an Array are written as a dimension`() {
        val pairs = listOf(intArrayOf(1, 2), intArrayOf(3, 4))
        assertEquals("integer[]", declaredType(pairs))
        assertEquals("{{1,2},{3,4}}", session.createNativeQuery("SELECT $1::text").fetchRowStrict(pairs).get<String>(0))

        val back: Array<IntArray> = roundTrip(arrayOf(intArrayOf(1, 2), intArrayOf(3, 4)))
        assertArrayEquals(intArrayOf(3, 4), back[1])
    }

    @Test
    fun `ragged nesting is refused with the position of the first level out of shape`() {
        fun refused(value: Any): MappingException {
            val ex = assertThrows<MappingException> { declaredType(value) }
            assertEquals(MappingExceptionReason.CONVERSION_ERROR, ex.reason)
            return ex
        }

        // The total matches the 3 x 2 the first entries suggest, so only a check per level catches it
        assertEquals(listOf("[1]"), refused(listOf(listOf(1, 2), listOf(3, 4, 5), listOf(6))).path.take(1))
        assertEquals(listOf("[1]"), refused(listOf(intArrayOf(1, 2), null)).path.take(1))
        assertEquals(listOf("[1]"), refused(listOf(1, listOf(2))).path.take(1))
        assertEquals(listOf("[1]", "[0]"), refused(listOf(listOf(listOf(1), listOf(2, 3)))).path.take(2))
    }

    @Test
    fun `a ByteArray inside a list stays a bytea element`() {
        assertEquals("bytea[]", declaredType(listOf(byteArrayOf(1, 2), byteArrayOf(3))))
    }

    @Test
    fun `an Array with no non-null element takes its type from the element class`() {
        assertEquals("text[]", declaredType(emptyArray<String>()))
        assertEquals("integer[]", declaredType(emptyArray<Int>()))
        assertEquals("allegiance[]", declaredType(emptyArray<Allegiance>()))
        assertEquals("allegiance[]", declaredType(arrayOfNulls<Allegiance>(2)))
        assertEquals("domicile[]", declaredType(emptyArray<Domicile>()))
        assertEquals("integer[]", declaredType(emptyArray<IntArray>()))

        val none: Array<Allegiance?> = roundTrip(arrayOfNulls<Allegiance>(2))
        assertArrayEquals(arrayOf<Allegiance?>(null, null), none)
    }

    @Test
    fun `an empty list still has no element type to go by`() {
        val ex = assertThrows<MappingException> { declaredType(emptyList<String>()) }

        assertTrue(
            (ex.cause as? TypeException)?.reason == TypeExceptionReason.TYPE_NOT_FOUND,
            "expected TYPE_NOT_FOUND underneath, got ${ex.cause}"
        )
    }
}
