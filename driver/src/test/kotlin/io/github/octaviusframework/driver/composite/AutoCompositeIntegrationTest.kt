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
 * multirange, sent as a parameter and read back - through a positional query and a named one alike. Then the
 * two shapes a composite takes that the catalog has to be read carefully for: a table's row type with a column
 * dropped out of its middle, and a composite that is `NULL` beside one whose every attribute is.
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

    /** The roll of a table whose middle column was dropped: `legion` and `cohorts` are attributes 1 and 3. */
    data class GarrisonRoll(val legion: String, val cohorts: Int)

    override val schema = """
        CREATE TYPE roman_name AS (praenomen text, nomen text);
        CREATE TYPE enlistment AS (
            name    roman_name,
            ranks   text[],
            service daterange,
            watches tsrange[],
            leave   datemultirange
        );

        CREATE TABLE garrison_roll (legion text, prefect text, cohorts int);
        ALTER TABLE garrison_roll DROP COLUMN prefect;
        INSERT INTO garrison_roll VALUES ('XX Valeria Victrix', 10);
    """.trimIndent()

    @BeforeAll
    fun register() {
        openSession().use { session ->
            session.typeManager.registerAutoComposite<RomanName>("roman_name")
            session.typeManager.registerAutoComposite<Enlistment>("enlistment")
            session.typeManager.registerAutoComposite<GarrisonRoll>("garrison_roll")
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

    @Test
    fun `a table's row type reads and writes around a column dropped from its middle`() {
        openSession().use { session ->
            val roll = session.createNativeQuery("SELECT g FROM garrison_roll g").fetchFieldStrict<GarrisonRoll>()
            assertEquals(GarrisonRoll("XX Valeria Victrix", 10), roll)

            val cohorts = session.createNativeQuery("SELECT ($1::garrison_roll).cohorts")
                .fetchFieldStrict<Int>(GarrisonRoll("II Augusta", 9))
            assertEquals(9, cohorts)
        }
    }

    @Test
    fun `a NULL composite is not a composite of NULLs`() {
        // SQL cannot tell them apart - ROW(NULL, NULL) IS NULL is true - but the wire can, and so does the
        // mapping: one is no value, the other is a value with nothing in it.
        openSession().use { session ->
            val row = session.createNativeQuery("SELECT NULL::roman_name, ROW(NULL, NULL)::roman_name").fetchRowStrict()

            assertEquals(null, row.get<Map<String, Any?>?>(0))
            assertEquals(mapOf("praenomen" to null, "nomen" to null), row.get<Map<String, Any?>?>(1))
        }
    }
}
