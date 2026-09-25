package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * A reflectively mapped composite carrying another composite, an array, a range, an array of ranges and a
 * multirange, sent as a parameter and read back - through a positional query and a named one alike.
 */
class AutoCompositeIntegrationTest : AbstractIntegrationTest() {

    data class RomanName(val praenomen: String, val nomen: String)

    data class Enlistment(
        val name: RomanName,
        val ranks: List<String>,
        val service: Range<LocalDate>,
        val watches: List<Range<LocalDateTime>>,
        val leave: MultiRange<LocalDate>
    )

    override val schema = """
        CREATE TYPE roman_name AS (praenomen text, nomen text);
        CREATE TYPE enlistment AS (
            name    roman_name,
            ranks   text[],
            service daterange,
            watches tsrange[],
            leave   datemultirange
        );
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<RomanName>("roman_name")
            session.typeManager.registerAutoComposite<Enlistment>("enlistment")
        }
    }

    /** Twenty-five years under the standards from AD 9, the first two watches of one night, two leaves. */
    private fun enlistment() = Enlistment(
        name = RomanName("Marcus", "Caelius"),
        ranks = listOf("miles", "centurio"),
        service = rangeOf(lowerBound = LocalDate(9, 1, 1), upperBound = LocalDate(34, 12, 31)),
        watches = listOf(
            rangeOf(lowerBound = LocalDateTime(9, 9, 8, 18, 0), upperBound = LocalDateTime(9, 9, 8, 21, 0)),
            rangeOf(lowerBound = LocalDateTime(9, 9, 8, 21, 0), upperBound = LocalDateTime(9, 9, 9, 0, 0))
        ),
        leave = multiRangeOf(
            rangeOf(lowerBound = LocalDate(10, 6, 1), upperBound = LocalDate(10, 6, 10)),
            rangeOf(lowerBound = LocalDate(10, 7, 1), upperBound = LocalDate(10, 7, 15))
        )
    )

    private fun assertReadBack(back: Enlistment) {
        assertEquals("Marcus", back.name.praenomen)
        assertEquals("Caelius", back.name.nomen)
        assertEquals(listOf("miles", "centurio"), back.ranks)

        assertEquals(LocalDate(9, 1, 1), back.service.lowerBound)
        assertEquals(LocalDate(34, 12, 31), back.service.upperBound)

        assertEquals(2, back.watches.size)
        assertEquals(LocalDateTime(9, 9, 8, 18, 0), back.watches[0].lowerBound)

        assertEquals(2, back.leave.ranges.size)
        assertEquals(LocalDate(10, 6, 1), back.leave.ranges[0].lowerBound)
    }

    @Test
    fun testEverythingWithNativeQuery() {
        openSession().use { session ->
            val row = session.createNativeQuery("SELECT $1 AS enlistment").fetchRowStrict(enlistment())

            assertReadBack(row.get<Enlistment>("enlistment"))
        }
    }

    @Test
    fun testEverythingWithNamedParameterQuery() {
        openSession().use { session ->
            val row = session.createNamedQuery("SELECT @enlistment AS enlistment")
                .fetchRowStrict("enlistment" to enlistment())

            assertReadBack(row.get<Enlistment>("enlistment"))
        }
    }
}
