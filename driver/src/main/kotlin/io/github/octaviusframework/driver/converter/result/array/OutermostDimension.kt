package io.github.octaviusframework.driver.converter.result.array

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The outermost dimension of [source], each entry converted to [elementType].
 *
 * With one dimension the entries are the elements themselves. With more, each entry is the sub-array one dimension
 * down, handed back to [context] like any other value - so what it becomes is decided by [elementType], one level
 * at a time, by whichever converter claims that type.
 */
internal fun convertOutermostDimension(
    source: PgArray,
    elementType: KType,
    sourceType: PgType,
    context: DeserializationContext
): List<Any?> {
    val dimensions = source.dimensions

    if (dimensions.size > 1) {
        val inner = dimensions.subList(1, dimensions.size)
        var stride = 1
        for (dimension in inner) stride *= dimension.size

        return List(dimensions[0].size) { i ->
            val slice = PgArray(source.arrayOid, source.elementOid, inner, source.elements.subList(i * stride, (i + 1) * stride))
            context.convert<Any?>(slice, elementType, sourceType, "[$i]")
        }
    }

    val pgElementType = context.types.dictionary.getPgType(source.elementOid)
    val elements = source.elements

    var elementConverter: ResultConverter<Any, *>? = null
    var isFallbackCast = false
    var converterSearched = false
    val kClassForCast = elementType.classifier as? KClass<*>

    return List(elements.size) { i ->
        val value = elements[i]
        if (value == null) {
            if (!elementType.isMarkedNullable) {
                val e = MappingException(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, "Null array element for non-nullable type $elementType")
                e.path.add("[$i]")
                throw e
            }
            null
        } else {
            if (!converterSearched) {
                elementConverter = context.findConverter(value::class, elementType, pgElementType)
                if (elementConverter == null) isFallbackCast = true
                converterSearched = true
            }
            if (isFallbackCast) {
                if (kClassForCast != null && kClassForCast.isInstance(value)) {
                    value
                } else {
                    val e = MappingException(MappingExceptionReason.CONVERSION_ERROR, details = "No converter found for source ${value::class} and expected type $elementType")
                    e.path.add("[$i]")
                    throw e
                }
            } else {
                try {
                    elementConverter!!.convert(value, elementType, pgElementType, context)
                } catch (e: MappingException) {
                    e.path.add("[$i]")
                    throw e
                }
            }
        }
    }
}
