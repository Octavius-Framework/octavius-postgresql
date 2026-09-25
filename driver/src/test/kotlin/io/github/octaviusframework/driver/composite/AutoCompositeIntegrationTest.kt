package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.range.rangeOf
import io.github.octaviusframework.driver.type.range.multiRangeOf
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AutoCompositeIntegrationTest : AbstractIntegrationTest() {

    data class PersonProfile(val firstName: String, val lastName: String)

    data class EmployeeData(
        val profile: PersonProfile,
        val roles: List<String>,
        val activePeriod: Range<LocalDate>,
        val scheduleShifts: List<Range<LocalDateTime>>,
        val availableDays: MultiRange<LocalDate>
    )

    override val schema = """
        CREATE TYPE person_profile AS (first_name text, last_name text);
        CREATE TYPE employee_data AS (
            profile person_profile,
            roles text[],
            active_period daterange,
            schedule_shifts tsrange[],
            available_days datemultirange
        );
    """.trimIndent()

    @Test
    fun testEverythingWithNativeQuery() {
        val session = openSession()
        try {
            session.reloadTypes()
            session.typeManager.registerAutoComposite<PersonProfile>("person_profile")
            session.typeManager.registerAutoComposite<EmployeeData>("employee_data")

            val activePeriod = rangeOf(
                lowerBound = LocalDate(2023, 1, 1),
                upperBound = LocalDate(2023, 12, 31)
            )

            val shift1 = rangeOf(
                lowerBound = LocalDateTime(2023, 5, 1, 8, 0),
                upperBound = LocalDateTime(2023, 5, 1, 16, 0)
            )
            val shift2 = rangeOf(
                lowerBound = LocalDateTime(2023, 5, 2, 9, 0),
                upperBound = LocalDateTime(2023, 5, 2, 17, 0)
            )

            val availableDays = multiRangeOf(
                rangeOf(lowerBound = LocalDate(2023, 6, 1), upperBound = LocalDate(2023, 6, 10)),
                rangeOf(lowerBound = LocalDate(2023, 7, 1), upperBound = LocalDate(2023, 7, 15))
            )

            val emp = EmployeeData(
                profile = PersonProfile("Jan", "Kowalski"),
                roles = listOf("admin", "user"),
                activePeriod = activePeriod,
                scheduleShifts = listOf(shift1, shift2),
                availableDays = availableDays
            )

            val query = "SELECT $1 AS emp"
            println("Sending EmployeeData Native: $emp")
            val resultRow = session.createNativeQuery(query).fetchRowStrict(emp)
            println("Result Row Native: $resultRow")

            val parsedEmp = resultRow.get<EmployeeData>("emp")
            println("Parsed EmployeeData Native: $parsedEmp")

            assertEquals("Jan", parsedEmp.profile.firstName)
            assertEquals("Kowalski", parsedEmp.profile.lastName)
            assertEquals(listOf("admin", "user"), parsedEmp.roles)
            
            assertEquals(LocalDate(2023, 1, 1), parsedEmp.activePeriod.lowerBound)
            assertEquals(LocalDate(2023, 12, 31), parsedEmp.activePeriod.upperBound)

            assertEquals(2, parsedEmp.scheduleShifts.size)
            assertEquals(LocalDateTime(2023, 5, 1, 8, 0), parsedEmp.scheduleShifts[0].lowerBound)
            
            assertEquals(2, parsedEmp.availableDays.ranges.size)
            assertEquals(LocalDate(2023, 6, 1), parsedEmp.availableDays.ranges[0].lowerBound)

        } finally {
            session.close()
        }
    }

    @Test
    fun testEverythingWithNamedParameterQuery() {
        val session = openSession()
        try {
            session.reloadTypes()
            session.typeManager.registerAutoComposite<PersonProfile>("person_profile")
            session.typeManager.registerAutoComposite<EmployeeData>("employee_data")

            val activePeriod = rangeOf(
                lowerBound = LocalDate(2023, 1, 1),
                upperBound = LocalDate(2023, 12, 31)
            )

            val shift1 = rangeOf(
                lowerBound = LocalDateTime(2023, 5, 1, 8, 0),
                upperBound = LocalDateTime(2023, 5, 1, 16, 0)
            )
            val shift2 = rangeOf(
                lowerBound = LocalDateTime(2023, 5, 2, 9, 0),
                upperBound = LocalDateTime(2023, 5, 2, 17, 0)
            )

            val availableDays = multiRangeOf(
                rangeOf(lowerBound = LocalDate(2023, 6, 1), upperBound = LocalDate(2023, 6, 10)),
                rangeOf(lowerBound = LocalDate(2023, 7, 1), upperBound = LocalDate(2023, 7, 15))
            )

            val emp = EmployeeData(
                profile = PersonProfile("Jan", "Kowalski"),
                roles = listOf("admin", "user"),
                activePeriod = activePeriod,
                scheduleShifts = listOf(shift1, shift2),
                availableDays = availableDays
            )

            val query = "SELECT @employee AS emp"
            val resultRow = session.createNamedQuery(query).fetchRowStrict("employee" to emp)

            val parsedEmp = resultRow.get<EmployeeData>("emp")

            assertEquals("Jan", parsedEmp.profile.firstName)
            assertEquals("Kowalski", parsedEmp.profile.lastName)
            assertEquals(listOf("admin", "user"), parsedEmp.roles)
            
            assertEquals(LocalDate(2023, 1, 1), parsedEmp.activePeriod.lowerBound)
            assertEquals(LocalDate(2023, 12, 31), parsedEmp.activePeriod.upperBound)

            assertEquals(2, parsedEmp.scheduleShifts.size)
            assertEquals(LocalDateTime(2023, 5, 1, 8, 0), parsedEmp.scheduleShifts[0].lowerBound)
            
            assertEquals(2, parsedEmp.availableDays.ranges.size)
            assertEquals(LocalDate(2023, 6, 1), parsedEmp.availableDays.ranges[0].lowerBound)
            
        } finally {
            session.close()
        }
    }
}
