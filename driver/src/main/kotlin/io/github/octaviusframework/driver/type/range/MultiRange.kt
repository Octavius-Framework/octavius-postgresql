package io.github.octaviusframework.driver.type.range

import kotlin.reflect.KClass

/**
 * A multirange for application code, read and written as the multirange type over [T].
 *
 * @property elementClass The class of the bounds, which picks the multirange type.
 */
data class MultiRange<T : Any>(
    val elementClass: KClass<T>,
    val ranges: List<Range<T>>
) {
    /** A multirange holding [ranges]. */
    constructor(elementClass: KClass<T>, vararg ranges: Range<T>) : this(elementClass, ranges.toList())

    companion object {
        /** The empty multirange over [T]. */
        inline fun <reified T : Any> empty(): MultiRange<T> = MultiRange(T::class, emptyList())
    }
}

/** A multirange over [T] holding [ranges], which need not be sorted or disjoint. */
inline fun <reified T : Any> multiRangeOf(vararg ranges: Range<T>): MultiRange<T> = MultiRange(T::class, ranges.toList())

/** A multirange over [T] holding [ranges], which need not be sorted or disjoint. */
inline fun <reified T : Any> multiRangeOf(ranges: List<Range<T>>): MultiRange<T> = MultiRange(T::class, ranges)
