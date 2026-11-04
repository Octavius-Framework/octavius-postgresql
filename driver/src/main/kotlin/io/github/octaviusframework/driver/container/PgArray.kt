package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason

/**
 * One dimension of an array.
 *
 * @property lowerBound The index this dimension starts at, usually 1.
 */
data class ArrayDimension(
    val size: Int,
    val lowerBound: Int
)

/**
 * A value of an array type.
 *
 * @property dimensions One entry per axis.
 * @property elements Every element, flattened in row-major order.
 */
class PgArray internal constructor(
    val arrayOid: Int,
    val elementOid: Int,
    val dimensions: List<ArrayDimension>,
    val elements: List<Any?>
) : PgContainer {
    override val containerOid: Int get() = arrayOid

    /** The number of elements across every dimension. */
    val totalElements: Int
        get() = elements.size

    /**
     * Returns the element at zero-based [index] of [elements], cast to [T]. In `val id: Int = array[0]` the expected
     * type fixes [T].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside [elements], `CONVERSION_ERROR`
     *   if the element is `null` under a non-nullable [T], or is not a [T].
     */
    inline operator fun <reified T> get(index: Int): T {
        if (index !in elements.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Element index out of bounds: $index (array holds ${elements.size} elements)"
        )
        val value = elements[index]
        if (value is T) return value
        throw conversionErrorAt(
            "[$index]",
            "Expected ${T::class.simpleName}, got ${if (value != null) value::class.simpleName else "null"}"
        )
    }
}

