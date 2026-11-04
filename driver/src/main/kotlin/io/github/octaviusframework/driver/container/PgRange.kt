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

    /** The lower bound is present but `NULL`, which is distinct from being unbounded. */
    val isLowerNull: Boolean get() = (flags.toInt() and 0x20) != 0

    /** The upper bound is present but `NULL`, which is distinct from being unbounded. */
    val isUpperNull: Boolean get() = (flags.toInt() and 0x40) != 0

    /**
     * Returns the lower bound, cast to [T]. An empty range, an unbounded side and a `NULL` bound all give `null`.
     *
     * @throws MappingException `CONVERSION_ERROR` if there is no bound and [T] is not nullable, or the
     *   bound is not a [T].
     */
    inline fun <reified T> lowerBound(): T {
        if (isEmpty || isLowerInfinite || isLowerNull) {
            if (null is T) return null as T
            throw conversionErrorAt(
                "lower",
                "Lower bound is null or infinite (missing) but requested type is non-nullable"
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
     * Returns the upper bound, cast to [T]. An empty range, an unbounded side and a `NULL` bound all give `null`.
     *
     * @throws MappingException `CONVERSION_ERROR` if there is no bound and [T] is not nullable, or the
     *   bound is not a [T].
     */
    inline fun <reified T> upperBound(): T {
        if (isEmpty || isUpperInfinite || isUpperNull) {
            if (null is T) return null as T
            throw conversionErrorAt(
                "upper",
                "Upper bound is null or infinite (missing) but requested type is non-nullable"
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
         * A range of [rangeOid]. The defaults give PostgreSQL's `[)`, and a `null` bound is unbounded unless
         * `isLowerNull` or `isUpperNull` says it is a `NULL`; unbounded wins where both are asked for.
         */
        fun create(
            rangeOid: Int,
            elementOid: Int,
            lowerBound: Any? = null,
            upperBound: Any? = null,
            isLowerInclusive: Boolean = true,
            isUpperInclusive: Boolean = false,
            isLowerInfinite: Boolean = (lowerBound == null),
            isUpperInfinite: Boolean = (upperBound == null),
            isLowerNull: Boolean = false,
            isUpperNull: Boolean = false
        ): PgRange {
            var flags = 0

            if (isLowerInclusive) flags = flags or 0x02
            if (isUpperInclusive) flags = flags or 0x04

            if (isLowerInfinite) {
                flags = flags or 0x08
            } else if (isLowerNull || lowerBound == null) {
                flags = flags or 0x20
            }

            if (isUpperInfinite) {
                flags = flags or 0x10
            } else if (isUpperNull || upperBound == null) {
                flags = flags or 0x40
            }

            return PgRange(rangeOid, elementOid, flags.toByte(), lowerBound, upperBound)
        }
    }
}

