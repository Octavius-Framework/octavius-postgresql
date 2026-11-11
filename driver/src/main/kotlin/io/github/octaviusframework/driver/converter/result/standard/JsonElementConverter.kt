package io.github.octaviusframework.driver.converter.result.standard

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.reflect.KType

internal object JsonElementConverter : ResultConverter<String, JsonElement> {

    override val supportedSourceClass = String::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        if (sourceType.schema != "pg_catalog" || (sourceType.name != "json" && sourceType.name != "jsonb")) return false
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == JsonElement::class || kClass == JsonObject::class || kClass == JsonArray::class ||
            kClass == JsonPrimitive::class || kClass == Any::class
    }

    override fun convert(
        source: String,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): JsonElement {
        return Json.parseToJsonElement(source)
    }
}
