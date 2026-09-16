package io.github.octaviusframework.driver.converter.result.range

import io.github.octaviusframework.driver.container.PgRange
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.range.Range
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object RangeResultConverter : ResultConverter<PgRange, Range<*>> {

    override val supportedSourceClass = PgRange::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Range::class || kClass == Any::class
    }

    override fun convert(source: PgRange, expectedType: KType, sourceType: PgType, context: DeserializationContext): Range<*> {
        val ktElementType = expectedType.arguments.firstOrNull()?.type 
            ?: typeOf<Any>()
        @Suppress("UNCHECKED_CAST")
        val elementClass = (ktElementType.classifier as? KClass<Any>) ?: Any::class
        
        val pgElementType = context.types.dictionary.getPgType(source.elementOid)

        val lower = source.lowerBound?.let { context.convert<Any>(it, ktElementType, pgElementType) }
        val upper = source.upperBound?.let { context.convert<Any>(it, ktElementType, pgElementType) }

        return Range(
            elementClass = elementClass,
            lowerBound = lower,
            upperBound = upper,
            isLowerInclusive = source.isLowerInclusive,
            isUpperInclusive = source.isUpperInclusive,
            isLowerInfinite = source.isLowerInfinite,
            isUpperInfinite = source.isUpperInfinite,
            isLowerNull = source.isLowerNull,
            isUpperNull = source.isUpperNull,
            isEmpty = source.isEmpty
        )
    }
}
