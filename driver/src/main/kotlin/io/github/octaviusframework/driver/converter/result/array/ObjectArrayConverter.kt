package io.github.octaviusframework.driver.converter.result.array

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object ObjectArrayConverter : ResultConverter<PgArray, Array<*>> {

    override val supportedSourceClass = PgArray::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val jClass = (expectedType.classifier as? KClass<*>)?.java ?: return false
        return jClass.isArray && !jClass.componentType.isPrimitive
    }

    override fun convert(source: PgArray, expectedType: KType, sourceType: PgType, context: DeserializationContext): Array<*> {
        val componentClass = (expectedType.classifier as KClass<*>).java.componentType
        val elementType = expectedType.arguments.firstOrNull()?.type ?: typeOf<Any?>()
        val elements = convertOutermostDimension(source, elementType, sourceType, context)

        @Suppress("UNCHECKED_CAST")
        val result = java.lang.reflect.Array.newInstance(componentClass, elements.size) as Array<Any?>
        for (i in elements.indices) result[i] = elements[i]
        return result
    }
}
