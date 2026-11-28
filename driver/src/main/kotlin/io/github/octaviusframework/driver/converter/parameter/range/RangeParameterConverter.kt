package io.github.octaviusframework.driver.converter.parameter.range

import io.github.octaviusframework.driver.container.PgRange
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.range.Range
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object RangeParameterConverter : ParameterConverter<Range<*>> {

    override val supportedClass: KClass<Range<*>> = Range::class

    override fun convert(source: Range<*>, expectedOid: Int, context: SerializationContext): Any {
        val pgType = if (expectedOid.isKnownOid) {
            val type = context.types.dictionary.getBaseType(expectedOid)
            type as? PgType.Range ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = expectedOid,
                details = "Type '${type.name}' is not a range"
            )
        } else {
            val elementClass = source.elementClass
            val elementOid = context.defaultOidForClass(elementClass)?.takeIf { it.isKnownOid } ?: throw TypeException(
                TypeExceptionReason.TYPE_NOT_FOUND,
                details = "No PostgreSQL type for range bounds of ${elementClass.qualifiedName ?: elementClass}: " +
                    "register the class (registerAutoComposite, registerEnum) or give its parameter converter a getDefaultTypeName"
            )
            context.types.dictionary.getRangeType(elementOid)
        }

        return pgRangeOf(source, pgType, context)
    }
}

/** [range] as a range of [rangeType], each bound converted for its subtype with `lower` or `upper` as its path. */
internal fun pgRangeOf(range: Range<*>, rangeType: PgType.Range, context: SerializationContext): PgRange {
    if (range.isEmpty) return context.types.containers.createEmptyRange(rangeType.oid)

    val subtypeOid = rangeType.subtypeOid
    return context.types.containers.createRange(
        oid = rangeType.oid,
        lower = range.lowerBound?.let { context.convert(it, subtypeOid, "lower") },
        upper = range.upperBound?.let { context.convert(it, subtypeOid, "upper") },
        isLowerInclusive = range.isLowerInclusive,
        isUpperInclusive = range.isUpperInclusive
    )
}
