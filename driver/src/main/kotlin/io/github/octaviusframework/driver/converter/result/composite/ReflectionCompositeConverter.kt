package io.github.octaviusframework.driver.converter.result.composite

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.util.reflection.ReflectionCache
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import io.github.octaviusframework.driver.util.reflection.MissingToken
import io.github.octaviusframework.driver.util.reflection.instantiateDataObject

internal object ReflectionCompositeConverter : ResultConverter<PgComposite, Any> {

    override val supportedSourceClass = PgComposite::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        if (kClass == Any::class) {
            return context.types.catalog.compositeClassByName.containsKey(QualifiedName(sourceType.schema, sourceType.name)) ||
                    context.types.catalog.compositeClassByName.containsKey(QualifiedName("", sourceType.name))
        }
        if (!kClass.isData) return false
        return context.types.catalog.registeredComposites.containsKey(kClass)
    }

    override fun convert(source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext): Any {
        val expectedClass = expectedType.classifier as? KClass<*> ?: Any::class
        
        @Suppress("UNCHECKED_CAST")
        val kClass = if (expectedClass == Any::class) {
            context.types.catalog.compositeClassByName[QualifiedName(sourceType.schema, sourceType.name)]
                ?: context.types.catalog.compositeClassByName[QualifiedName("", sourceType.name)]
                ?: error("Missing composite registration for type")
        } else {
            expectedClass
        } as KClass<Any>

        val metadata = ReflectionCache.getOrCreateDataObjectMetadata(kClass)

        return instantiateDataObject(kClass, metadata) { meta ->
            val columnName = meta.keyName
            val index = source.type.nameToIndex[columnName] ?: -1

            if (index != -1) {
                val rawValue = source.get<Any?>(index)
                if (rawValue == null) {
                    null
                } else {
                    context.convert<Any>(rawValue, meta.type, source.getAttributeOid(index), columnName)
                }
            } else {
                MissingToken
            }
        }
    }
}