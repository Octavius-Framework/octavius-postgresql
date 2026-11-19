package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException

/**
 * A value of a range type, such as `int4range` or `tsrange`, as its codec reads and writes it.
 *
 * @property elementOid OID of the bound type.
 * @property flags The protocol's flag byte, which the `is…` properties read.
 * @property lowerBound The lower bound, `null` where there is none.
 * @property upperBound The upper bound, `null` where there is none.
 */
class PgRange internal constructor(
    val rangeOid: Int,
    val elementOid: Int,
    val flags: Byte,
    val lowerBound: Any?,
    val upperBound: Any?
) : PgContainer {
    override val containerOid: Int get() = rangeOid

    /** The range contains nothing. The other flags mean nothing then. */
    val isEmpty: Boolean get() = (flags.toInt() and 0x01) != 0

    /** The lower bound is part of the range - `[` rather than `(` in PostgreSQL's own notation. */
    val isLowerInclusive: Boolean get() = (flags.toInt() and 0x02) != 0

    /** The upper bound is part of the range - `]` rather than `)`. */
    val isUpperInclusive: Boolean get() = (flags.toInt() and 0x04) != 0

    /** The range extends without limit downwards, written `(,` in PostgreSQL. */
    val isLowerInfinite: Boolean get() = (flags.toInt() and 0x08) != 0

    /** The range extends without limit upwards, written `,)`. */
    val isUpperInfinite: Boolean get() = (flags.toInt() and 0x10) != 0

    /**
     * Returns the lower bound, cast to [T]. An empty range and an unbounded side give `null`.
     *
     * @throws MappingException `CONVERSION_ERROR` if there is no bound and [T] is not nullable, or the
     *   bound is not a [T].
     */
    inline fun <reified T> lowerBound(): T {
        if (isEmpty || isLowerInfinite) {
            if (null is T) return null as T
            throw conversionErrorAt(
                "lower",
                "Lower bound is missing (infinite, or the range is empty) but requested type is non-nullable"
            )
        }
        val value = lowerBound
        if (value is T) return value
        throw conversionErrorAt(
            "lower",
            "Expected ${T::class.simpleName}, got ${if (value != null) value::class.simpleName else "null"}"
        )
    }

    /**
     * Returns the upper bound, cast to [T]. An empty range and an unbounded side give `null`.
     *
     * @throws MappingException `CONVERSION_ERROR` if there is no bound and [T] is not nullable, or the
     *   bound is not a [T].
     */
    inline fun <reified T> upperBound(): T {
        if (isEmpty || isUpperInfinite) {
            if (null is T) return null as T
            throw conversionErrorAt(
                "upper",
                "Upper bound is missing (infinite, or the range is empty) but requested type is non-nullable"
            )
        }
        val value = upperBound
        if (value is T) return value
        throw conversionErrorAt(
            "upper",
            "Expected ${T::class.simpleName}, got ${if (value != null) value::class.simpleName else "null"}"
        )
    }

    companion object {
        /** The empty range of [rangeOid], whose bounds are of [elementOid]. */
        fun empty(rangeOid: Int, elementOid: Int): PgRange {
            return PgRange(
                rangeOid = rangeOid,
                elementOid = elementOid,
                flags = 0x01,
                lowerBound = null,
                upperBound = null
            )
        }

        /**
         * A range of [rangeOid]. A `null` bound makes its side infinite, and the defaults give PostgreSQL's `[)`,
         * the lower side inclusive only where it has a bound.
         */
        fun create(
            rangeOid: Int,
            elementOid: Int,
            lowerBound: Any? = null,
            upperBound: Any? = null,
            isLowerInclusive: Boolean = lowerBound != null,
            isUpperInclusive: Boolean = false
        ): PgRange {
            var flags = 0

            if (isLowerInclusive) flags = flags or 0x02
            if (isUpperInclusive) flags = flags or 0x04
            if (lowerBound == null) flags = flags or 0x08
            if (upperBound == null) flags = flags or 0x10

            return PgRange(rangeOid, elementOid, flags.toByte(), lowerBound, upperBound)
        }
    }
}

