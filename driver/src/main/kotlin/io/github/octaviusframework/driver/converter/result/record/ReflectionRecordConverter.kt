package io.github.octaviusframework.driver.converter.result.record

import io.github.octaviusframework.driver.container.PgRecord
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.util.reflection.MissingToken
import io.github.octaviusframework.driver.util.reflection.ReflectionCache
import io.github.octaviusframework.driver.util.reflection.instantiateDataObject
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Builds the data class asked for from an anonymous `ROW(...)` of alternating keys and values, each property from
 * the value under its name, as a row's columns are read. A key with no property is ignored; the fields are refused
 * as [MapRecordConverter] refuses them.
 */
internal object ReflectionRecordConverter : ResultConverter<PgRecord, Any> {

    private val keyType = typeOf<String>()

    override val supportedSourceClass = PgRecord::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass.isData
    }

    override fun convert(source: PgRecord, expectedType: KType, sourceType: PgType, context: DeserializationContext): Any {
        @Suppress("UNCHECKED_CAST")
        val kClass = expectedType.classifier as KClass<Any>

        val metadata = ReflectionCache.getOrCreateDataObjectMetadata(kClass)
        val valueIndexes = valueIndexByKey(source, keyType, context)

        return instantiateDataObject(kClass, metadata) { meta ->
            val key = meta.keyName
            val index = valueIndexes[key] ?: return@instantiateDataObject MissingToken

            val rawValue = source.fields[index]
            if (rawValue == null) {
                null
            } else {
                context.convert<Any>(rawValue, meta.type, source.getAttributeOid(index), key)
            }
        }
    }
}
