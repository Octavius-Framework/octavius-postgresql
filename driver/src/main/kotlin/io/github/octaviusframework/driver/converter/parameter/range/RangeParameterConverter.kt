package io.github.octaviusframework.driver.converter.parameter.range

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

        val elementOid = pgType.subtypeOid
        val boundConverter = context.findConverterByClass(source.elementClass, elementOid)

        val convertedLower = source.lowerBound?.let { boundConverter?.convert(it, elementOid, context) ?: it }
        val convertedUpper = source.upperBound?.let { boundConverter?.convert(it, elementOid, context) ?: it }

        if (source.isEmpty) {
            return context.types.containers.createEmptyRange(pgType.oid)
        }

        return context.types.containers.createRange(
            oid = pgType.oid,
            lower = convertedLower,
            upper = convertedUpper,
            isLowerInclusive = source.isLowerInclusive,
            isUpperInclusive = source.isUpperInclusive
        )
    }
}
