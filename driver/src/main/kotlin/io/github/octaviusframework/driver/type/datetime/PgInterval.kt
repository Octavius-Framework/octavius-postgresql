package io.github.octaviusframework.driver.type.datetime


import io.github.octaviusframework.driver.type.datetime.PgInterval.*
import io.github.octaviusframework.type.datetime.INFINITY
import io.github.octaviusframework.type.datetime.MINUS_INFINITY
import kotlinx.datetime.DateTimePeriod
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** An `interval`, finite or infinite. */
sealed interface PgInterval {
    /**
     * A finite interval, in PostgreSQL's three fields.
     *
     * @property time The time part, in microseconds.
     */
    data class Finite(
        val time: Long,
        val days: Int,
        val months: Int
    ) : PgInterval

    /** `infinity`. */
    data object Infinity : PgInterval

    /** `-infinity`. */
    data object MinusInfinity : PgInterval
}

/**
 * This interval as a [DateTimePeriod].
 *
 * @throws IllegalArgumentException if the time overflows the period's nanoseconds, past about 2.5 million hours.
 */
fun PgInterval.toDateTimePeriod(): DateTimePeriod = when (this) {
    Infinity -> DateTimePeriod.INFINITY
    MinusInfinity -> DateTimePeriod.MINUS_INFINITY
    is Finite -> {
        val years = months / 12
        val remainingMonths = months % 12

        val hours = (time / 3_600_000_000L).toInt()
        val remainderAfterHours = time % 3_600_000_000L

        val minutes = (remainderAfterHours / 60_000_000L).toInt()
        val remainderAfterMinutes = remainderAfterHours % 60_000_000L

        val seconds = (remainderAfterMinutes / 1_000_000L).toInt()
        val microseconds = remainderAfterMinutes % 1_000_000L

        DateTimePeriod(
            years = years,
            months = remainingMonths,
            days = days,
            hours = hours,
            minutes = minutes,
            seconds = seconds,
            nanoseconds = microseconds * 1000
        )
    }
}

/**
 * This period as a [PgInterval].
 *
 * @throws IllegalArgumentException if the months or the time overflow.
 */
fun DateTimePeriod.toPgInterval(): PgInterval = when (this) {
    DateTimePeriod.INFINITY -> Infinity
    DateTimePeriod.MINUS_INFINITY -> MinusInfinity
    else -> {
        val pgMonths = try {
            Math.addExact(Math.multiplyExact(years, 12), months)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Months overflow in DateTimePeriod to PgInterval conversion", e)
        }

        val pgTime = try {
            var t = Math.multiplyExact(hours.toLong(), 3_600_000_000L)
            t = Math.addExact(t, Math.multiplyExact(minutes.toLong(), 60_000_000L))
            t = Math.addExact(t, Math.multiplyExact(seconds.toLong(), 1_000_000L))
            t = Math.addExact(t, nanoseconds / 1000L)
            t
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Time overflow in DateTimePeriod to PgInterval conversion", e)
        }

        Finite(
            time = pgTime,
            days = days,
            months = pgMonths
        )
    }
}

// ==========================================
// 3. PG_INTERVAL <-> DURATION (STRICT)
// ==========================================

/**
 * This interval as a [Duration].
 *
 * @throws IllegalArgumentException if it has days or months, whose length varies.
 */
fun PgInterval.toDurationExact(): Duration = when (this) {
    Infinity -> Duration.INFINITE
    MinusInfinity -> -Duration.INFINITE
    is Finite -> {
        if (days != 0 || months != 0) {
            throw IllegalArgumentException(
                "Cannot convert PgInterval to exact Duration because it contains variable-length calendar units (days: $days, months: $months)."
            )
        }
        time.microseconds
    }
}

/** This duration as an interval of [PgInterval.Finite.time] alone. */
fun Duration.toPgIntervalExact(): PgInterval = when {
    this == Duration.INFINITE -> Infinity
    this == -Duration.INFINITE -> MinusInfinity
    else -> Finite(
        time = this.inWholeMicroseconds,
        days = 0,
        months = 0
    )
}

// ==========================================
// 4. PG_INTERVAL <-> DURATION (APPROXIMATE / JUSTIFIED)
// ==========================================

private const val MICROS_PER_DAY = 86_400_000_000L
private const val MICROS_PER_MONTH = 30 * MICROS_PER_DAY // 2,592,000,000,000L

/**
 * This interval as a [Duration], counting a month as 30 days and a day as 24 hours, as `justify_interval` does.
 *
 * @throws IllegalArgumentException if the microseconds overflow.
 */
fun PgInterval.toDurationApproximate(): Duration = when (this) {
    Infinity -> Duration.INFINITE
    MinusInfinity -> -Duration.INFINITE
    is Finite -> {
        val totalMicros = try {
            var m = Math.multiplyExact(months.toLong(), MICROS_PER_MONTH)
            m = Math.addExact(m, Math.multiplyExact(days.toLong(), MICROS_PER_DAY))
            m = Math.addExact(m, time)
            m
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Microseconds overflow in PgInterval to Duration conversion", e)
        }
        totalMicros.microseconds
    }
}

/** This duration as months of 30 days, days of 24 hours and the rest, as `justify_interval` splits it. */
fun Duration.toPgIntervalApproximate(): PgInterval = when {
    this == Duration.INFINITE -> Infinity
    this == -Duration.INFINITE -> MinusInfinity
    else -> {
        val totalMicros = this.inWholeMicroseconds
        val months = (totalMicros / MICROS_PER_MONTH).toInt()
        val remAfterMonths = totalMicros % MICROS_PER_MONTH

        val days = (remAfterMonths / MICROS_PER_DAY).toInt()
        val time = remAfterMonths % MICROS_PER_DAY

        Finite(
            time = time,
            days = days,
            months = months
        )
    }
}