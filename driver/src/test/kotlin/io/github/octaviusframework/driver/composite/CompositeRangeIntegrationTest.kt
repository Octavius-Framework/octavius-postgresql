package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompositeRangeIntegrationTest : AbstractIntegrationTest() {

    data class SimpleData(val major: Int, val minor: Int)

    override val schema = """
        CREATE TYPE simple_data AS (major int, minor int);
        CREATE TYPE simple_data_range AS RANGE (subtype = simple_data);
    """.trimIndent()

    @Test
    fun testCompositeRangeNativeQuery() {
        val session = openSession()
        try {
            session.reloadTypes()
            session.typeManager.registerAutoComposite<SimpleData>("simple_data")

            val dataRange = rangeOf(
                lowerBound = SimpleData(10, 10),
                upperBound = SimpleData(10, 20)
            )

            val query = "SELECT $1 AS data_range"
            val resultRow = session.createNativeQuery(query).fetchRowStrict(dataRange)
            val parsedRange = resultRow.get<Range<SimpleData>>("data_range")

            assertEquals(10, parsedRange.lowerBound?.minor)
            assertEquals(20, parsedRange.upperBound?.minor)
        } finally {
            session.close()
        }
    }

    @Test
    fun testCompositeMultiRangeNativeQuery() {
        val session = openSession()
        try {
            session.reloadTypes()
            session.typeManager.registerAutoComposite<SimpleData>("simple_data")

            val dataRange1 = rangeOf(
                lowerBound = SimpleData(10, 10),
                upperBound = SimpleData(10, 20)
            )

            val dataRange2 = rangeOf(
                lowerBound = SimpleData(10, 30),
                upperBound = SimpleData(10, 40)
            )

            val multiRange = multiRangeOf(dataRange1, dataRange2)

            val query = "SELECT $1 AS data_range"
            val resultRow = session.createNativeQuery(query).fetchRowStrict(multiRange)
            val parsedMultiRange = resultRow.get<MultiRange<SimpleData>>("data_range")

            assertEquals(2, parsedMultiRange.ranges.size)
            assertEquals(10, parsedMultiRange.ranges[0].lowerBound?.minor)
            assertEquals(20, parsedMultiRange.ranges[0].upperBound?.minor)
            assertEquals(30, parsedMultiRange.ranges[1].lowerBound?.minor)
            assertEquals(40, parsedMultiRange.ranges[1].upperBound?.minor)
        } finally {
            session.close()
        }
    }
}
