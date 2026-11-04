package io.github.octaviusframework.driver.type.range

import kotlin.reflect.KClass

/**
 * A range for application code, read and written as the range type over [T]. The defaults give PostgreSQL's
 * `[)`, and a `null` bound is unbounded unless `isLowerNull` or `isUpperNull` says it is a `NULL`.
 *
 * @property elementClass The class of the bounds, which picks the range type.
 * @property isEmpty Whether the range contains nothing; [Range.empty] builds one.
 */
data class Range<T : Any>(
    val elementClass: KClass<T>,
    val lowerBound: T? = null,
    val upperBound: T? = null,
    val isLowerInclusive: Boolean = true,
    val isUpperInclusive: Boolean = false,
    val isLowerInfinite: Boolean = lowerBound == null,
    val isUpperInfinite: Boolean = upperBound == null,
    val isLowerNull: Boolean = false,
    val isUpperNull: Boolean = false,
    val isEmpty: Boolean = false
) {
    companion object {
        /** The empty range over [T]. */
        inline fun <reified T : Any> empty(): Range<T> = Range(
            elementClass = T::class,
            lowerBound = null,
            upperBound = null,
            isLowerInclusive = false,
            isUpperInclusive = false,
            isLowerInfinite = false,
            isUpperInfinite = false,
            isLowerNull = false,
            isUpperNull = false,
            isEmpty = true
        )
    }
}

/**
 * A range over [T]. Where a custom range type shares its subtype with a built-in one, the custom one wins; pin
 * the type with [withPgType][io.github.octaviusframework.driver.type.withPgType] where that matters.
 */
inline fun <reified T : Any> rangeOf(
    lowerBound: T? = null,
    upperBound: T? = null,
    isLowerInclusive: Boolean = true,
    isUpperInclusive: Boolean = false,
    isLowerInfinite: Boolean = lowerBound == null,
    isUpperInfinite: Boolean = upperBound == null,
    isLowerNull: Boolean = false,
    isUpperNull: Boolean = false
): Range<T> = Range(
    elementClass = T::class,
    lowerBound = lowerBound,
    upperBound = upperBound,
    isLowerInclusive = isLowerInclusive,
    isUpperInclusive = isUpperInclusive,
    isLowerInfinite = isLowerInfinite,
    isUpperInfinite = isUpperInfinite,
    isLowerNull = isLowerNull,
    isUpperNull = isUpperNull,
    isEmpty = false
)
