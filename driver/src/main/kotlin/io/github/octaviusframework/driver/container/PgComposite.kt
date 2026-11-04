package io.github.octaviusframework.driver.container

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType

/**
 * A value of a composite type, its attributes as their codecs read them.
 *
 * @property fields The attribute values, in declaration order.
 */
class PgComposite internal constructor(
    val type: PgType.Composite,
    val fields: Array<Any?>
) : PgContainer {
    override val containerOid: Int get() = type.oid

    /** The names of this composite's attributes, in declaration order. */
    val attributeNames: List<String>
        get() = type.attributeNames

    /**
     * Returns the attribute at zero-based [index], cast to [T]. In `val id: Int = composite[0]` the expected type
     * fixes [T].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside this composite's attributes,
     *   `CONVERSION_ERROR` if the value is `null` under a non-nullable [T], or is not a [T].
     */
    inline operator fun <reified T> get(index: Int): T {
        if (index !in fields.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Attribute index out of bounds: $index in composite '${type.name}' (${fields.size} attributes)"
        )

        val value = fields[index]

        if (value is T) {
            return value
        }

        if (value == null) {
            throw conversionErrorAt(
                type.attributeNames[index],
                "Expected non-null value for attribute at index $index, got null"
            )
        }

        throw conversionErrorAt(
            type.attributeNames[index],
            "Expected ${T::class.simpleName}, got ${value::class.simpleName}"
        )
    }

    /**
     * The zero-based index of the attribute [columnName].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if this composite has no such attribute.
     */
    fun getColumnIndex(columnName: String): Int {
        val index = type.nameToIndex[columnName] ?: -1
        if (index == -1) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Attribute: $columnName"
        )
        return index
    }

    /**
     * Sets the attribute at [index]. Its type is checked only when the composite is sent.
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside this composite's attributes.
     */
    operator fun set(index: Int, newValue: Any?) {
        if (index !in fields.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Attribute index out of bounds: $index in composite '${type.name}' (${fields.size} attributes)"
        )
        fields[index] = newValue
    }

    /**
     * Sets the attribute named [columnName].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if this composite has no such attribute.
     */
    operator fun set(columnName: String, newValue: Any?) {
        set(getColumnIndex(columnName), newValue)
    }

    /**
     * Returns the attribute named [name], cast to [T]. In `val name: String = composite["cognomen"]` the expected
     * type fixes [T].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if there is no such attribute, `CONVERSION_ERROR` if
     *   the value is `null` under a non-nullable [T], or is not a [T].
     */
    inline operator fun <reified T> get(name: String): T {
        val index = type.nameToIndex[name] ?: -1
        if (index == -1) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Attribute '$name' in composite '${type.name}'"
        )
        return get<T>(index)
    }

    /**
     * The type OID of the attribute at [index].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is outside this composite's attributes.
     */
    fun getAttributeOid(index: Int): Int {
        val attributeOids = type.attributeOids
        if (index !in attributeOids.indices) throw MappingException(
            MappingExceptionReason.COLUMN_NOT_FOUND,
            details = "Attribute index out of bounds: $index in composite '${type.name}' (${attributeOids.size} attributes)"
        )
        return attributeOids[index]
    }

    /**
     * The type OID of the attribute named [name].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if this composite has no such attribute.
     */
    fun getAttributeOid(name: String): Int {
        return getAttributeOid(getColumnIndex(name))
    }
}

