package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.container.PgMultirange
import io.github.octaviusframework.driver.container.PgRange
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason

/**
 * A factory for creating PostgreSQL container types such as composites, ranges, and multiranges.
 *
 * It utilizes the underlying [TypeLookup] to resolve types by name or OID and instantiates
 * the appropriate container representations.
 *
 * @property types the lookup used for resolving types and reading the catalog.
 */
class ContainerFactory internal constructor(
    private val types: TypeLookup
) {

    /**
     * Creates a new instance of a PostgreSQL composite type using its name and schema.
     *
     * @param typeName The name of the composite type.
     * @param schema The schema where the composite type is defined.
     * @return A new [io.github.octaviusframework.driver.container.PgComposite] instance with empty fields.
     */
    fun createComposite(typeName: String, schema: String = ""): PgComposite {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createComposite(resolvedOid)
    }

    /**
     * Creates a new instance of a PostgreSQL composite type using its Object ID (OID).
     *
     * @param oid The OID of the composite type.
     * @return A new [PgComposite] instance with empty fields.
     */
    fun createComposite(oid: Int): PgComposite {
        val pgType = types.dictionary.getPgType(oid) as? PgType.Composite
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a composite")
        val fields = Array<Any?>(pgType.attributes.size) { null }
        return PgComposite(pgType, fields)
    }


    /**
     * Creates a new instance of a PostgreSQL range type using its name and schema.
     *
     * @param typeName The name of the range type.
     * @param schema The schema where the range type is defined.
     * @param lower The lower bound value.
     * @param upper The upper bound value.
     * @param isLowerInclusive Whether the lower bound is inclusive.
     * @param isUpperInclusive Whether the upper bound is inclusive.
     * @param isLowerInfinite Whether the lower bound is infinite.
     * @param isUpperInfinite Whether the upper bound is infinite.
     * @param isLowerNull Whether the lower bound is null.
     * @param isUpperNull Whether the upper bound is null.
     * @return A new [io.github.octaviusframework.driver.container.PgRange] instance.
     */
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

    /**
     * Creates a new instance of a PostgreSQL range type using its Object ID (OID).
     *
     * @param oid The OID of the range type.
     * @param lower The lower bound value.
     * @param upper The upper bound value.
     * @param isLowerInclusive Whether the lower bound is inclusive.
     * @param isUpperInclusive Whether the upper bound is inclusive.
     * @param isLowerInfinite Whether the lower bound is infinite.
     * @param isUpperInfinite Whether the upper bound is infinite.
     * @param isLowerNull Whether the lower bound is null.
     * @param isUpperNull Whether the upper bound is null.
     * @return A new [PgRange] instance.
     * @throws TypeException if the type is not found or is not a range.
     */
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

    /**
     * Creates an empty PostgreSQL range type using its name and schema.
     * 
     * @param typeName The name of the range type.
     * @param schema The schema where the range type is defined (defaults to empty for search path).
     * @return An empty [PgRange] instance.
     * @throws TypeException if the type cannot be found.
     */
    fun createEmptyRange(typeName: String, schema: String = ""): PgRange {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createEmptyRange(resolvedOid)
    }

    /**
     * Creates an empty PostgreSQL range type using its Object ID (OID).
     * 
     * @param oid The OID of the range type.
     * @return An empty [PgRange] instance.
     * @throws TypeException if the type is not found or is not a range.
     */
    fun createEmptyRange(oid: Int): PgRange {
        val rangeType = types.dictionary.getPgType(oid) as? PgType.Range
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a range")
        return PgRange.empty(rangeType.oid, rangeType.subtypeOid)
    }

    /**
     * Creates a new instance of a PostgreSQL multirange type using its name and schema.
     *
     * @param typeName The name of the multirange type.
     * @param schema The schema where the multirange type is defined.
     * @param ranges The ranges included in the multirange.
     * @return A new [io.github.octaviusframework.driver.container.PgMultirange] instance.
     */
    fun createMultirange(typeName: String, schema: String = "", vararg ranges: PgRange): PgMultirange {
        val resolvedOid = types.resolveOid(typeName, schema)
        return createMultirange(resolvedOid, *ranges)
    }

    /**
     * Creates a new instance of a PostgreSQL multirange type using its Object ID (OID).
     *
     * @param oid The OID of the multirange type.
     * @param ranges The ranges included in the multirange.
     * @return A new [PgMultirange] instance.
     */
    fun createMultirange(oid: Int, vararg ranges: PgRange): PgMultirange {
        val multirangeType = types.dictionary.getPgType(oid) as? PgType.Multirange
            ?: throw TypeException(TypeExceptionReason.NOT_A_CONTAINER, oid = oid, details = "Type is not a multirange")
        return PgMultirange(multirangeType.oid, multirangeType.rangeOid, ranges.toList())
    }
}
