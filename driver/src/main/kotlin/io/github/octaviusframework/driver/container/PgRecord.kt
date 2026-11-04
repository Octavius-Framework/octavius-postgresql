package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType

/**
 * An anonymous `record`, such as `ROW(...)`, whose fields have positions and no names.
 *
 * @property fieldOids The type OID of each field.
 */
class PgRecord internal constructor(
    val type: PgType.Record,
    val fieldOids: IntArray,
    val fields: Array<Any?>
) : PgContainer {
    override val containerOid: Int get() = type.oid

    /**
     * Returns the field at zero-based [index], cast to [T]. In `val id: Int = record[0]` the expected type fixes [T].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside this record's fields,
     *   `CONVERSION_ERROR` if the value is `null` under a non-nullable [T], or is not a [T].
     */
    inline operator fun <reified T> get(index: Int): T {
        if (index !in fields.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Field index out of bounds: $index (record holds ${fields.size} fields)"
        )

        val value = fields[index]

        if (value is T) {
            return value
        }

        if (value == null) {
            // A record's fields have no names, so the position names them, as in an array.
            throw conversionErrorAt("[$index]", "Expected non-null value for attribute at index $index, got null")
        }

        throw conversionErrorAt("[$index]", "Expected ${T::class.simpleName}, got ${value::class.simpleName}")
    }

    /**
     * The type OID of the field at [index].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside this record's fields.
     */
    fun getAttributeOid(index: Int): Int {
        if (index !in fieldOids.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Field index out of bounds: $index (record holds ${fieldOids.size} fields)"
        )
        return fieldOids[index]
    }
}


