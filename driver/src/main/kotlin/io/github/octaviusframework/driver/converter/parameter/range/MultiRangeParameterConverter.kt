package io.github.octaviusframework.driver.converter.parameter.range

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object MultiRangeParameterConverter : ParameterConverter<MultiRange<*>> {

    override val supportedClass: KClass<MultiRange<*>> = MultiRange::class

    override fun convert(source: MultiRange<*>, expectedOid: Int, context: SerializationContext): Any {
        val pgType = if (expectedOid.isKnownOid) {
            val type = context.types.dictionary.getBaseType(expectedOid)
            type as? PgType.Multirange ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = expectedOid,
                details = "Type '${type.name}' is not a multirange"
            )
        } else {
            val elementClass = source.elementClass
            val elementOid = context.defaultOidForClass(elementClass)?.takeIf { it.isKnownOid } ?: throw TypeException(
                TypeExceptionReason.TYPE_NOT_FOUND,
                details = "No PostgreSQL type for multirange bounds of ${elementClass.qualifiedName ?: elementClass}: " +
                    "register the class (registerAutoComposite, registerEnum) or give its parameter converter a getDefaultTypeName"
            )
            val rangeType = context.types.dictionary.getRangeType(elementOid)
            context.types.dictionary.getMultirangeType(rangeType.oid)
        }

        val rangeType = context.types.dictionary.getPgType(pgType.rangeOid) as PgType.Range

        val pgRanges = Array(source.ranges.size) { i ->
            try {
                pgRangeOf(source.ranges[i], rangeType, context)
            } catch (e: MappingException) {
                e.path.add("[$i]")
                throw e
            }
        }

        return context.types.containers.createMultirange(pgType.oid, *pgRanges)
    }
}
