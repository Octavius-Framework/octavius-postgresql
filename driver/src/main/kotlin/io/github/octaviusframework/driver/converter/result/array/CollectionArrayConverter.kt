package io.github.octaviusframework.driver.converter.result.array

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object CollectionArrayConverter : ResultConverter<PgArray, Collection<*>> {

    override val supportedSourceClass = PgArray::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*>
        return kClass == List::class || kClass == Collection::class || kClass == Iterable::class || kClass == Set::class || kClass == Any::class
    }

    override fun convert(source: PgArray, expectedType: KType, sourceType: PgType, context: DeserializationContext): Collection<*> {
        val pgElementType = context.types.dictionary.getPgType(source.elementOid)

        return buildMultiDimensionalCollection(source, context, expectedType, 0, 0, pgElementType)
    }

    private fun buildMultiDimensionalCollection(
        source: PgArray,
        context: DeserializationContext,
        expectedType: KType,
        dimensionIndex: Int,
        flatIndexOffset: Int,
        pgElementType: PgType
    ): Collection<*> {
        val kClass = expectedType.classifier as? KClass<*> ?: List::class
        val ktElementType = expectedType.arguments.firstOrNull()?.type ?: typeOf<Any?>()

        val elements = source.elements

        if (source.dimensions.isEmpty()) {
            val mappedElements = List(elements.size) { i ->
                val value = elements[i]
                if (value == null) {
                    if (!ktElementType.isMarkedNullable) {
                        val e = MappingException(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, "Null array element for non-nullable type $ktElementType")
                        e.path.add("[$i]")
                        throw e
                    }
                    null
                } else context.convert<Any>(value, ktElementType, pgElementType, "[$i]")
            }
            return if (kClass == Set::class) mappedElements.toSet() else mappedElements
        }

        val currentDimSize = source.dimensions[dimensionIndex].size

        var elementConverter: ResultConverter<Any, *>? = null
        var isFallbackCast = false
        var converterSearched = false
        val kClassForCast = ktElementType.classifier as? KClass<*>

        val mappedElements = if (dimensionIndex == source.dimensions.size - 1) {
            List(currentDimSize) { i ->
                val flatIndex = flatIndexOffset + i
                val value = elements[flatIndex]
                if (value == null) {
                    if (!ktElementType.isMarkedNullable) {
                        val e = MappingException(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, "Null array element for non-nullable type $ktElementType")
                        e.path.add("[$i]")
                        throw e
                    }
                    null
                } else {
                    if (!converterSearched) {
                        elementConverter = context.findConverter(value::class, ktElementType, pgElementType)
                        if (elementConverter == null) isFallbackCast = true
                        converterSearched = true
                    }
                    if (isFallbackCast) {
                        if (kClassForCast != null && kClassForCast.isInstance(value)) {
                            value
                        } else {
                            val e = MappingException(MappingExceptionReason.CONVERSION_ERROR, details = "No converter found for source ${value::class} and expected type $ktElementType")
                            e.path.add("[$i]")
                            throw e
                        }
                    } else {
                        try {
                            elementConverter!!.convert(value, ktElementType, pgElementType, context)
                        } catch (e: MappingException) {
                            e.path.add("[$i]")
                            throw e
                        }
                    }
                }
            }
        } else {
            var multiplier = 1
            for (j in dimensionIndex + 1 until source.dimensions.size) {
                multiplier *= source.dimensions[j].size
            }

            List(currentDimSize) { i ->
                try {
                    buildMultiDimensionalCollection(
                        source,
                        context,
                        ktElementType,
                        dimensionIndex + 1,
                        flatIndexOffset + i * multiplier,
                        pgElementType
                    )
                } catch (e: MappingException) {
                    e.path.add("[$i]")
                    throw e
                }
            }
        }

        return if (kClass == Set::class) mappedElements.toSet() else mappedElements
    }
}