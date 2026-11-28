package io.github.octaviusframework.driver.converter.result.range

import io.github.octaviusframework.driver.container.PgMultirange
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.type.range.MultiRange
import io.github.octaviusframework.driver.type.PgType
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
        val elementType = expectedType.arguments.firstOrNull()?.type ?: typeOf<Any>()
        @Suppress("UNCHECKED_CAST")
        val elementClass = (elementType.classifier as? KClass<Any>) ?: Any::class

        val ranges = source.ranges.mapIndexed { i, pgRange ->
            try {
                rangeFrom(pgRange, elementType, context)
            } catch (e: MappingException) {
                e.path.add("[$i]")
                throw e
            }
        }

        return MultiRange(elementClass, ranges)
    }
}
