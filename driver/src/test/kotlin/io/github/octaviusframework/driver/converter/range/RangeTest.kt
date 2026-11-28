package io.github.octaviusframework.driver.converter.range

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgStandardType
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeTest : AbstractIntegrationTest() {

    @Test
    fun `a range reads as Range`() {
        val session = openSession()

        val rangeResult = session.createNativeQuery("SELECT '[10,20)'::int4range").fetchRowStrict()
        val range = rangeResult.get<Range<Int>>(0)
        assertNotNull(range)
        assertEquals(10, range.lowerBound)
        assertEquals(20, range.upperBound)
        assertTrue(range.isLowerInclusive)
        assertFalse(range.isUpperInclusive)
        session.close()
    }

    @Test
    fun `a multirange reads as MultiRange`() {
        val session = openSession()

        val multiRangeResult = session.createNativeQuery("SELECT '{[1,5], [10,20)}'::int4multirange").fetchRowStrict()
        val multiRange = multiRangeResult.get<MultiRange<Int>>(0)
        assertNotNull(multiRange)
        assertEquals(2, multiRange.ranges.size)

        val first = multiRange.ranges[0]
        assertEquals(1, first.lowerBound)
        assertEquals(6, first.upperBound) // Normalized from [1,5] to [1,6)
        assertTrue(first.isLowerInclusive)
        assertFalse(first.isUpperInclusive)

        val second = multiRange.ranges[1]
        assertEquals(10, second.lowerBound)
        assertEquals(20, second.upperBound)
        assertTrue(second.isLowerInclusive)
        assertFalse(second.isUpperInclusive)
        session.close()
    }

    @Test
    fun `a Range goes out as a range`() {
        val session = openSession()

        val inputRange = rangeOf(lowerBound = 5, upperBound = 15, isLowerInclusive = true, isUpperInclusive = true)

        val result = session.createNativeQuery("SELECT $1::int4range").fetchRowStrict(inputRange)
        val rangeBack = result.get<Range<Int>>(0)

        assertNotNull(rangeBack)
        assertEquals(5, rangeBack.lowerBound)
        assertEquals(16, rangeBack.upperBound) // Normalized [5,15] to [5,16)
        assertTrue(rangeBack.isLowerInclusive)
        assertFalse(rangeBack.isUpperInclusive)
        session.close()
    }

    @Test
    fun `a range with no lower bound reads back equal to the one sent`() {
        val session = openSession()

        val sent = rangeOf<Int>(upperBound = 10)

        assertEquals(sent, session.createNativeQuery("SELECT $1::int4range").fetchRowStrict(sent).get<Range<Int>>(0))
        session.close()
    }

    @Test
    fun `a MultiRange goes out as a multirange, an unbounded side included`() {
        val session = openSession()

        val inputMultiRange = multiRangeOf(
            listOf(
                rangeOf(lowerBound = 1, upperBound = 5, isLowerInclusive = true, isUpperInclusive = false),
                rangeOf(lowerBound = 10, upperBound = null, isLowerInclusive = false)
            )
        )

        val result = session.createNativeQuery("SELECT $1::int4multirange").fetchRowStrict(inputMultiRange)
        val multiRangeBack = result.get<MultiRange<Int>>(0)

        assertNotNull(multiRangeBack)
        assertEquals(2, multiRangeBack.ranges.size)

        val first = multiRangeBack.ranges[0]
        assertEquals(1, first.lowerBound)
        assertEquals(5, first.upperBound)
        assertTrue(first.isLowerInclusive)
        assertFalse(first.isUpperInclusive)

        val second = multiRangeBack.ranges[1]
        assertEquals(11, second.lowerBound) // Normalized from (10, infinity) to [11, infinity)
        assertNull(second.upperBound)
        assertTrue(second.isLowerInclusive)
        assertTrue(second.isUpperInfinite)
        session.close()
    }

    /** A data class with no type registered for it. */
    data class Milestone(val road: Int, val mile: Int)

    private fun typeExceptionFor(param: Any): TypeException = openSession().use { session ->
        val ex = assertFailsWith<MappingException> { session.createNativeQuery("SELECT $1").fetchRowStrict(param) }
        assertIs<TypeException>(ex.cause)
    }

    @Test
    fun `a Range or MultiRange sent as a type of another kind is refused as not that container`() {
        val asInt = typeExceptionFor(rangeOf(1, 5).withPgType(PgStandardType.INT4))
        assertEquals(TypeExceptionReason.NOT_A_CONTAINER, asInt.reason)
        assertEquals("Type 'int4' is not a range", asInt.details)

        val asRange = typeExceptionFor(multiRangeOf(rangeOf(1, 5)).withPgType("int4range"))
        assertEquals(TypeExceptionReason.NOT_A_CONTAINER, asRange.reason)
        assertEquals("Type 'int4range' is not a multirange", asRange.details)
    }

    @Test
    fun `a Range or MultiRange over a class with no PostgreSQL type is refused naming the class`() {
        val milestone = Milestone::class.qualifiedName!!

        val range = typeExceptionFor(rangeOf(Milestone(1, 10), Milestone(1, 20)))
        assertEquals(TypeExceptionReason.TYPE_NOT_FOUND, range.reason)
        assertContains(range.details!!, "range bounds of $milestone")

        val multirange = typeExceptionFor(MultiRange.empty<Milestone>())
        assertEquals(TypeExceptionReason.TYPE_NOT_FOUND, multirange.reason)
        assertContains(multirange.details!!, "multirange bounds of $milestone")
    }

    private fun refusedParameter(param: Any): MappingException = openSession().use { session ->
        assertFailsWith<MappingException> { session.createNativeQuery("SELECT $1").fetchRowStrict(param) }
    }

    @Test
    fun `a bound that cannot be sent is named by its side`() {
        val lower = refusedParameter(rangeOf("V", "X").withPgType("int4range"))
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, lower.reason)
        assertEquals(listOf("\$1", "lower"), lower.path.asReversed())

        val upper = refusedParameter(rangeOf<String>(upperBound = "X").withPgType("int4range"))
        assertEquals(listOf("\$1", "upper"), upper.path.asReversed())
    }

    @Test
    fun `a bound that cannot be sent in a multirange is named by its range and its side`() {
        val refused = refusedParameter(
            multiRangeOf(rangeOf<String>(upperBound = "III"), rangeOf("V", "X")).withPgType("int4multirange")
        )

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, refused.reason)
        assertEquals(listOf("\$1", "[0]", "upper"), refused.path.asReversed())
    }

    private inline fun <reified T> refusedColumn(sql: String): MappingException = openSession().use { session ->
        assertFailsWith<MappingException> { session.createNativeQuery(sql).fetchFieldStrict<T>() }
    }

    @Test
    fun `a bound that cannot be read is named by its side`() {
        val lower = refusedColumn<Range<String>>("SELECT '[1,5)'::int4range AS term")
        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, lower.reason)
        assertEquals(listOf("term", "lower"), lower.path.asReversed())

        val upper = refusedColumn<Range<String>>("SELECT '(,5)'::int4range AS term")
        assertEquals(listOf("term", "upper"), upper.path.asReversed())
    }

    @Test
    fun `a bound that cannot be read in a multirange is named by its range and its side`() {
        val refused = refusedColumn<MultiRange<String>>("SELECT '{(,3), [7,9)}'::int4multirange AS terms")

        assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, refused.reason)
        assertEquals(listOf("terms", "[0]", "upper"), refused.path.asReversed())
    }
}