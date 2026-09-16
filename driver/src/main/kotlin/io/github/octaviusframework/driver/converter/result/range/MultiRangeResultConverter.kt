package io.github.octaviusframework.driver.converter.result.range

import io.github.octaviusframework.driver.container.PgMultirange
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.range.Range
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object MultiRangeResultConverter : ResultConverter<PgMultirange, MultiRange<*>> {

    override val supportedSourceClass = PgMultirange::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == MultiRange::class || kClass == Any::class
    }

    override fun convert(source: PgMultirange, expectedType: KType, sourceType: PgType, context: DeserializationContext): MultiRange<*> {
        val ktElementType = expectedType.arguments.firstOrNull()?.type 
            ?: typeOf<Any>()
        @Suppress("UNCHECKED_CAST")
        val elementClass = (ktElementType.classifier as? KClass<Any>) ?: Any::class

        if (source.ranges.isEmpty()) {
            return MultiRange(elementClass, emptyList())
        }

        val elementOid = source.ranges.first().elementOid
        val pgElementType = context.types.dictionary.getPgType(elementOid)

        val convertedRanges = source.ranges.map { pgRange ->
            val lower = pgRange.lowerBound?.let { context.convert<Any>(it, ktElementType, pgElementType) }
            val upper = pgRange.upperBound?.let { context.convert<Any>(it, ktElementType, pgElementType) }

            Range(
                elementClass = elementClass,
                lowerBound = lower,
                upperBound = upper,
                isLowerInclusive = pgRange.isLowerInclusive,
                isUpperInclusive = pgRange.isUpperInclusive,
                isLowerInfinite = pgRange.isLowerInfinite,
                isUpperInfinite = pgRange.isUpperInfinite,
                isLowerNull = pgRange.isLowerNull,
                isUpperNull = pgRange.isUpperNull,
                isEmpty = pgRange.isEmpty
            )
        }

        return MultiRange(elementClass, convertedRanges)
    }
}
