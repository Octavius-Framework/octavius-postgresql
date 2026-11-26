package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * A position in the write-ahead log, as `pg_lsn` holds it: an unsigned 64-bit byte offset, written `X/Y` with the
 * upper and lower 32 bits in hexadecimal. Ordered as unsigned.
 */
class PgLsn(val value: Long) : Comparable<PgLsn> {

    override fun compareTo(other: PgLsn): Int = java.lang.Long.compareUnsigned(value, other.value)

    override fun equals(other: Any?): Boolean = other is PgLsn && other.value == value

    override fun hashCode(): Int = value.hashCode()

    /** `X/Y`, as the server writes it. */
    override fun toString(): String =
        "${(value ushr 32).toString(16).uppercase()}/${(value and 0xFFFFFFFFL).toString(16).uppercase()}"

    companion object {
        /** `0/0`, which no record has; a stream started from it begins where its slot was last acknowledged. */
        @JvmField
        val INVALID = PgLsn(0)

        /**
         * The position [text] writes as `X/Y`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [text] is not of that form.
         */
        @JvmStatic
        fun parse(text: String): PgLsn {
            val match = FORMAT.matchEntire(text) ?: throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "'$text' is not a WAL position; one is written X/Y, both halves hexadecimal and at most 8 digits."
            )
            val (upper, lower) = match.destructured
            return PgLsn((upper.toLong(16) shl 32) or lower.toLong(16))
        }

        private val FORMAT = Regex("([0-9A-Fa-f]{1,8})/([0-9A-Fa-f]{1,8})")
    }
}
