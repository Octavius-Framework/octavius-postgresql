package io.github.octaviusframework.driver.type

import io.github.octaviusframework.driver.type.datetime.PgInterval
import io.github.octaviusframework.driver.type.datetime.toDateTimePeriod
import io.github.octaviusframework.driver.type.datetime.toDurationApproximate
import io.github.octaviusframework.driver.type.datetime.toDurationExact
import io.github.octaviusframework.driver.type.datetime.toPgInterval
import io.github.octaviusframework.driver.type.datetime.toPgIntervalApproximate
import io.github.octaviusframework.driver.type.datetime.toPgIntervalExact
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.datetime.DateTimePeriod
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

class PgIntervalIntegrationTest : AbstractIntegrationTest() {

    @Test
    fun `test finite PgInterval roundtrip via DB`() {
        val session = openSession()

        val finiteInterval = PgInterval.Finite(
            time = 3600_000_000L + 500_000L, // 1 hour + 0.5s in microseconds
            days = 15,
            months = 14 // 1 year and 2 months
        )

        val result = session.createNativeQuery("SELECT $1 as term_of_office")
            .fetchRowStrict(finiteInterval)
            
        assertEquals(finiteInterval, result.get(0))
        session.close()
    }

    @Test
    fun `test PgInterval infinity mappings via DB`() {
        val session = openSession()

        val result = session.createNativeQuery("SELECT $1 as sine_fine, $2 as ante_urbem")
            .fetchRowStrict(PgInterval.Infinity, PgInterval.MinusInfinity)

        assertEquals(PgInterval.Infinity, result.get(0))
        assertEquals(PgInterval.MinusInfinity, result.get(1))
        session.close()
    }

    @Test
    fun `test Postgres string to PgInterval`() {
        val session = openSession()

        val result = session.createNativeQuery("SELECT '1 year 2 months 15 days 01:00:00.5'::interval")
            .fetchRowStrict()

        val expected = PgInterval.Finite(
            time = 3600_000_000L + 500_000L, // 1 hour + 0.5 sec
            days = 15,
            months = 14
        )
        assertEquals(expected, result.get(0))
        session.close()
    }
    
    @Test
    fun `test infinity from Postgres string`() {
        val session = openSession()

        val result = session.createNativeQuery("SELECT 'infinity'::interval as sine_fine, '-infinity'::interval as ante_urbem")
            .fetchRowStrict()

        assertEquals(PgInterval.Infinity, result.get(0))
        assertEquals(PgInterval.MinusInfinity, result.get(1))
        session.close()
    }

    @Test
    fun `test DateTimePeriod to PgInterval roundtrip`() {
        val session = openSession()

        val period =DateTimePeriod(
            years = 2,
            months = 3,
            days = 10,
            hours = 14,
            minutes = 30,
            seconds = 15,
            nanoseconds = 500_000_000 // 0.5s
        )
        val interval = period.toPgInterval()

        val result = session.createNativeQuery("SELECT $1 as term_of_office")
            .fetchRowStrict(interval)
            
        assertEquals(interval, result.get(0))
        assertEquals(period, (result.get<PgInterval>(0)).toDateTimePeriod())
        session.close()
    }

    @Test
    fun `test Duration exact to PgInterval roundtrip`() {
        val session = openSession()

        val duration = Duration.parseIsoString("PT45H30M15.123S")
        val interval = duration.toPgIntervalExact()

        val result = session.createNativeQuery("SELECT $1 as term_of_office")
            .fetchRowStrict(interval)
            
        assertEquals(interval, result.get(0))
        assertEquals(duration, result.get<PgInterval>(0).toDurationExact())
        session.close()
    }

    @Test
    fun `test Duration approximate to PgInterval roundtrip`() {
        val session = openSession()

        // 35 days in hours
        val duration = Duration.parseIsoString("PT840H")
        val interval = duration.toPgIntervalApproximate()

        val result = session.createNativeQuery("SELECT $1 as term_of_office")
            .fetchRowStrict(interval)
            
        assertEquals(interval, result.get(0))
        assertEquals(duration, result.get<PgInterval>(0).toDurationApproximate())
        session.close()
    }

    @Test
    fun `test edge cases for negative periods and durations`() {
        val session = openSession()

        val negativePeriod = DateTimePeriod(
            years = -1,
            months = -5,
            days = -10,
            hours = -2,
            minutes = -30
        )
        val intervalPeriod = negativePeriod.toPgInterval()
        
        val negativeDuration = Duration.parseIsoString("-PT15H30M")
        val intervalDuration = negativeDuration.toPgIntervalExact()

        val result = session.createNativeQuery("SELECT $1 as exile, $2 as recall")
            .fetchRowStrict(intervalPeriod, intervalDuration)
            
        assertEquals(intervalPeriod, result.get(0))
        assertEquals(intervalDuration, result.get(1))
        assertEquals(negativePeriod, result.get<PgInterval>(0).toDateTimePeriod())
        assertEquals(negativeDuration, result.get<PgInterval>(1).toDurationExact())
        session.close()
    }
}
