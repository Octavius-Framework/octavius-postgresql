package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason

/**
 * A value of a multirange type. One read from the server holds its ranges disjoint and in ascending order.
 *
 * @property rangeOid OID of the range type it holds.
 */
class PgMultirange internal constructor(
    val multirangeOid: Int,
    val rangeOid: Int,
    val ranges: List<PgRange>
) : PgContainer {
    override val containerOid: Int get() = multirangeOid

    /** How many ranges this multirange holds. */
    val size: Int get() = ranges.size

    /**
     * The range at [index].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside [ranges].
     */
    operator fun get(index: Int): PgRange {
        if (index !in ranges.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Range index out of bounds: $index (multirange holds ${ranges.size} ranges)"
        )
        return ranges[index]
    }

    /** [ranges] itself, not a copy. */
    fun toList(): List<PgRange> = ranges

    companion object {
        /** A multirange of [multirangeOid] holding [ranges] as given; the server normalizes them. */
        fun create(
            multirangeOid: Int,
            rangeOid: Int,
            ranges: List<PgRange>
        ): PgMultirange {
            return PgMultirange(multirangeOid, rangeOid, ranges)
        }
    }
}
