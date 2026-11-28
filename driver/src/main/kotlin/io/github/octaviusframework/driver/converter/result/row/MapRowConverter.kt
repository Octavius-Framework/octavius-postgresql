package io.github.octaviusframework.driver.converter.result.row

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.util.reflection.requireStringKeys
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object MapRowConverter : ResultConverter<Row, Map<String, Any?>> {

    override val supportedSourceClass = Row::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Map::class
    }

    override fun convert(source: Row, expectedType: KType, sourceType: PgType, context: DeserializationContext): Map<String, Any?> {
        requireStringKeys(expectedType, "A row is keyed by its column names")
        val valueType = expectedType.arguments.getOrNull(1)?.type ?: typeOf<Any?>()

        val result = mutableMapOf<String, Any?>()
        val columns = source.metadata.columns
        for (index in columns.indices) {
            val column = columns[index]
            result[column.name] = context.convert<Any?>(source.getRaw(index), valueType, column.type, column.name)
        }
        return result
    }
}