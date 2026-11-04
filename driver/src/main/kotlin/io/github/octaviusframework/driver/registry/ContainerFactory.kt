package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.container.PgMultirange
import io.github.octaviusframework.driver.container.PgRange
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason

/**
 * Builds composites, ranges and multiranges of a type given by OID, or by name through the search path or in
 * `schema`. A missing type raises [TypeException] `TYPE_NOT_FOUND`, and a type of the wrong kind raises
 * `NOT_A_CONTAINER`.
 */
class ContainerFactory internal constructor(
    private val types: TypeLookup
) {

    /** A composite of [typeName] with every attribute `null`. */
    fun createComposite(typeName: String, schema: String = ""): PgComposite {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createComposite(resolvedOid)
    }

    /** A composite of [oid] with every attribute `null`. */
    fun createComposite(oid: Int): PgComposite {
        val pgType = types.dictionary.getPgType(oid) as? PgType.Composite
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a composite")
        val fields = Array<Any?>(pgType.attributes.size) { null }
        return PgComposite(pgType, fields)
    }


    /** A range of [typeName], with bounds as in [PgRange.create]. */
    fun createRange(
        typeName: String,
        schema: String = "",
        lower: Any? = null,
        upper: Any? = null,
        isLowerInclusive: Boolean = true,
        isUpperInclusive: Boolean = false,
        isLowerInfinite: Boolean = (lower == null),
        isUpperInfinite: Boolean = (upper == null),
        isLowerNull: Boolean = false,
        isUpperNull: Boolean = false
    ): PgRange {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createRange(
            oid = resolvedOid,
            lower = lower,
            upper = upper,
            isLowerInclusive = isLowerInclusive,
            isUpperInclusive = isUpperInclusive,
            isLowerInfinite = isLowerInfinite,
            isUpperInfinite = isUpperInfinite,
            isLowerNull = isLowerNull,
            isUpperNull = isUpperNull
        )
    }

    /** A range of [oid], with bounds as in [PgRange.create]. */
    fun createRange(
        oid: Int,
        lower: Any? = null,
        upper: Any? = null,
        isLowerInclusive: Boolean = true,
        isUpperInclusive: Boolean = false,
        isLowerInfinite: Boolean = (lower == null),
        isUpperInfinite: Boolean = (upper == null),
        isLowerNull: Boolean = false,
        isUpperNull: Boolean = false
    ): PgRange {
        val rangeType = types.dictionary.getPgType(oid) as? PgType.Range
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a range")
            
        return PgRange.create(
            rangeOid = rangeType.oid,
            elementOid = rangeType.subtypeOid,
            lowerBound = lower,
            upperBound = upper,
            isLowerInclusive = isLowerInclusive,
            isUpperInclusive = isUpperInclusive,
            isLowerInfinite = isLowerInfinite,
            isUpperInfinite = isUpperInfinite,
            isLowerNull = isLowerNull,
            isUpperNull = isUpperNull
        )
    }

    /** The empty range of [typeName]. */
    fun createEmptyRange(typeName: String, schema: String = ""): PgRange {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createEmptyRange(resolvedOid)
    }

    /** The empty range of [oid]. */
    fun createEmptyRange(oid: Int): PgRange {
        val rangeType = types.dictionary.getPgType(oid) as? PgType.Range
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a range")
        return PgRange.empty(rangeType.oid, rangeType.subtypeOid)
    }

    /** A multirange of [typeName] holding [ranges]. */
    fun createMultirange(typeName: String, schema: String = "", vararg ranges: PgRange): PgMultirange {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createMultirange(resolvedOid, *ranges)
    }

    /** A multirange of [oid] holding [ranges]. */
    fun createMultirange(oid: Int, vararg ranges: PgRange): PgMultirange {
        val multirangeType = types.dictionary.getPgType(oid) as? PgType.Multirange
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a multirange")
        return PgMultirange(multirangeType.oid, multirangeType.rangeOid, ranges.toList())
    }
}
