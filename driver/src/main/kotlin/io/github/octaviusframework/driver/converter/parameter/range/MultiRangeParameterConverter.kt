package io.github.octaviusframework.driver.converter.parameter.range

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.UNRESOLVED_OID
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object MultiRangeParameterConverter : ParameterConverter<MultiRange<*>> {

    override val supportedClass: KClass<MultiRange<*>> = MultiRange::class

    override fun convert(source: MultiRange<*>, expectedOid: Int, context: SerializationContext): Any {
        val types = context.types

        val pgType = if (expectedOid.isKnownOid) {
            context.types.dictionary.getPgType(expectedOid) as? PgType.Multirange
        } else {
            val elementOid = context.findConverterByClass(source.elementClass, UNRESOLVED_OID)?.getDefaultTypeName(source.elementClass, context)
                ?.let { context.types.resolveOid(it.name, it.schema, it.isArray) }
                ?.takeIf { it.isKnownOid }
                ?: types.codecs.getCodecByClass(source.elementClass)?.let { types.codecs.getOidForCodec(it) ?: types.resolveOid(it.pgTypeName, it.pgSchema) }

            if (elementOid != null && elementOid.isKnownOid) {
                val rangeType = context.types.dictionary.getRangeType(elementOid)
                context.types.dictionary.getMultirangeType(rangeType.oid)
            } else null
        }

        if (pgType == null) {
            throw TypeException(
                TypeExceptionReason.TYPE_NOT_FOUND,
                details = "Cannot infer multirange type. The multirange is empty or bounds are null. Use explicit typing (e.g. .withPgType(...))."
            )
        }

        val rangeOid = pgType.rangeOid
        val rangePgType = context.types.dictionary.getPgType(rangeOid) as PgType.Range
        val elementOid = rangePgType.subtypeOid
        val boundConverter = context.findConverterByClass(source.elementClass, elementOid)

        val pgRanges = source.ranges.map { range ->
            val convertedLower = range.lowerBound?.let { boundConverter?.convert(it, elementOid, context) ?: it }
            val convertedUpper = range.upperBound?.let { boundConverter?.convert(it, elementOid, context) ?: it }

            if (range.isEmpty) {
                context.types.containers.createEmptyRange(rangeOid)
            } else {
                context.types.containers.createRange(
                    oid = rangeOid,
                    lower = convertedLower,
                    upper = convertedUpper,
                    isLowerInclusive = range.isLowerInclusive,
                    isUpperInclusive = range.isUpperInclusive,
                    isLowerInfinite = range.isLowerInfinite,
                    isUpperInfinite = range.isUpperInfinite,
                    isLowerNull = range.isLowerNull,
                    isUpperNull = range.isUpperNull
                )
            }
        }

        return context.types.containers.createMultirange(pgType.oid, *pgRanges.toTypedArray())
    }
}
