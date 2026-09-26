package io.github.octaviusframework.driver.converter.result.array

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object CollectionArrayConverter : ResultConverter<PgArray, Collection<*>> {

    override val supportedSourceClass = PgArray::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val jClass = (expectedType.classifier as? KClass<*>)?.java ?: return false
        return jClass.isAssignableFrom(ArrayList::class.java) || jClass.isAssignableFrom(LinkedHashSet::class.java)
    }

    override fun convert(source: PgArray, expectedType: KType, sourceType: PgType, context: DeserializationContext): Collection<*> {
        val elementType = expectedType.arguments.firstOrNull()?.type ?: typeOf<Any?>()
        val elements = convertOutermostDimension(source, elementType, sourceType, context)
        val jClass = (expectedType.classifier as KClass<*>).java
        return if (jClass.isAssignableFrom(ArrayList::class.java)) elements else LinkedHashSet(elements)
    }
}
