package io.github.octaviusframework.driver.type.range

import kotlin.reflect.KClass

/**
 * A range for application code, read and written as the range type over [T]. A `null` bound makes its side
 * infinite, and the defaults give PostgreSQL's `[)`. An infinite side is never inclusive, so the lower side
 * defaults to inclusive only where it has a bound; the server drops the flag where one is asked for.
 *
 * @property elementClass The class of the bounds, which picks the range type.
 * @property isEmpty Whether the range contains nothing; [Range.empty] builds one.
 */
data class Range<T : Any>(
    val elementClass: KClass<T>,
    val lowerBound: T? = null,
    val upperBound: T? = null,
    val isLowerInclusive: Boolean = lowerBound != null,
    val isUpperInclusive: Boolean = false,
    val isEmpty: Boolean = false
) {
    /** The range extends without limit downwards: it is not empty and has no lower bound. */
    val isLowerInfinite: Boolean get() = !isEmpty && lowerBound == null

    /** The range extends without limit upwards: it is not empty and has no upper bound. */
    val isUpperInfinite: Boolean get() = !isEmpty && upperBound == null

    companion object {
        /** The empty range over [T]. */
        inline fun <reified T : Any> empty(): Range<T> = Range(
            elementClass = T::class,
            lowerBound = null,
            upperBound = null,
            isLowerInclusive = false,
            isUpperInclusive = false,
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
    isLowerInclusive: Boolean = lowerBound != null,
    isUpperInclusive: Boolean = false
): Range<T> = Range(
    elementClass = T::class,
    lowerBound = lowerBound,
    upperBound = upperBound,
    isLowerInclusive = isLowerInclusive,
    isUpperInclusive = isUpperInclusive,
    isEmpty = false
)
