package io.github.octaviusframework.driver.converter.result.record



import io.github.octaviusframework.driver.container.PgRecord
import io.github.octaviusframework.driver.container.conversionErrorAt
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Reads an anonymous `ROW(...)` as a map of alternating keys and values, each converted to the `Map`'s type
 * arguments, or as `Any` where there are none. An odd number of fields, a `NULL` key and a repeated key are
 * refused.
 */
internal object MapRecordConverter : ResultConverter<PgRecord, Map<Any, Any?>> {

    override val supportedSourceClass = PgRecord::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Map::class || kClass == Any::class
    }

    override fun convert(source: PgRecord, expectedType: KType, sourceType: PgType, context: DeserializationContext): Map<Any, Any?> {
        // Under `Any` or `Map<*, *>` both halves are read as `Any`.
        val asMap = expectedType.classifier == Map::class
        val keyType = (if (asMap) expectedType.arguments.getOrNull(0)?.type else null) ?: typeOf<Any>()
        val valueType = (if (asMap) expectedType.arguments.getOrNull(1)?.type else null) ?: typeOf<Any?>()

        val valueIndexes = valueIndexByKey(source, keyType, context)
        val result = LinkedHashMap<Any, Any?>(valueIndexes.size)

        for ((key, index) in valueIndexes) {
            // A value's path names its key.
            val valueRaw = source.fields[index]
            result[key] = if (valueRaw == null) {
                null
            } else {
                context.convert(valueRaw, valueType, source.getAttributeOid(index), key.toString())
            }
        }

        return result
    }
}

/**
 * The fields of [record] taken as alternating keys and values: each key converted to [keyType], in the order read,
 * mapped to the index of the value after it.
 *
 * @throws MappingException `CONVERSION_ERROR` for an odd number of fields, a `NULL` key or a repeated key; whatever
 *   converting a key raises, with its position as the path.
 */
internal fun valueIndexByKey(record: PgRecord, keyType: KType, context: DeserializationContext): LinkedHashMap<Any, Int> {
    val fields = record.fields

    if (fields.size % 2 != 0) throw MappingException(
        MappingExceptionReason.CONVERSION_ERROR,
        details = "Record fields must be in key-value pairs (even number of fields expected), got ${fields.size}"
    )

    val result = LinkedHashMap<Any, Int>(fields.size)

    for (i in fields.indices step 2) {
        // A key's path names its position.
        val keyRaw = fields[i] ?: throw conversionErrorAt(
            "[$i]",
            "A record read by its keys cannot carry a NULL key: the field at index $i is null"
        )

        val key: Any = context.convert(keyRaw, keyType, record.getAttributeOid(i), "[$i]")

        if (result.containsKey(key)) throw conversionErrorAt(
            "[$i]",
            "Duplicate key '$key': the field at index ${i + 1} would replace the one already read under it"
        )

        result[key] = i + 1
    }

    return result
}
