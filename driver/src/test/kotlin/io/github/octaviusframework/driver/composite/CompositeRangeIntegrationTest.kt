package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/** A range, and a multirange, over a composite: stretches of road measured between milestones. */
class CompositeRangeIntegrationTest : AbstractIntegrationTest() {

    data class Milestone(val road: Int, val mile: Int)

    override val schema = """
        CREATE TYPE milestone AS (road int, mile int);
        CREATE TYPE road_stretch AS RANGE (subtype = milestone);
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { it.typeManager.registerAutoComposite<Milestone>("milestone") }
    }

    @Test
    fun testCompositeRangeNativeQuery() {
        openSession().use { session ->
            val stretch = rangeOf(
                lowerBound = Milestone(1, 10),
                upperBound = Milestone(1, 20)
            )

            val row = session.createNativeQuery("SELECT $1 AS stretch").fetchRowStrict(stretch)
            val parsed = row.get<Range<Milestone>>("stretch")

            assertEquals(10, parsed.lowerBound?.mile)
            assertEquals(20, parsed.upperBound?.mile)
        }
    }

    @Test
    fun testCompositeMultiRangeNativeQuery() {
        openSession().use { session ->
            val stretches = multiRangeOf(
                rangeOf(lowerBound = Milestone(1, 10), upperBound = Milestone(1, 20)),
                rangeOf(lowerBound = Milestone(1, 30), upperBound = Milestone(1, 40))
            )

            val row = session.createNativeQuery("SELECT $1 AS stretches").fetchRowStrict(stretches)
            val parsed = row.get<MultiRange<Milestone>>("stretches")

            assertEquals(2, parsed.ranges.size)
            assertEquals(10, parsed.ranges[0].lowerBound?.mile)
            assertEquals(20, parsed.ranges[0].upperBound?.mile)
            assertEquals(30, parsed.ranges[1].lowerBound?.mile)
            assertEquals(40, parsed.ranges[1].upperBound?.mile)
        }
    }
}
