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
 * Reads an anonymous `ROW(...)` as a map, taking its fields as alternating key/value pairs.
 *
 * Both halves of a pair go through [DeserializationContext.convert] against the type arguments of the `Map`
 * asked for, so the key is converted like any value: `Map<String, Any?>` wants text keys, `Map<Int, String>`
 * wants `int4` ones, and `Map<Tribute, Int>` wants a registered composite. A key whose column converts to
 * none of that fails where it is met, instead of being stringified into something that looks like it worked.
 * Asked for as `Any`, or as a `Map` with nothing said about its arguments, each half is what the chain makes
 * of it with nothing narrower named — a registered composite still arriving as its class, not as the
 * [PgComposite][io.github.octaviusframework.driver.container.PgComposite] its codec produced.
 *
 * Three shapes are refused rather than guessed at, because the map would quietly hold fewer fields than the
 * record did otherwise: an odd number of fields, a `NULL` key, and a key already in the map. The last is
 * worth having even where keys look distinct in SQL, since two of them only have to be *equal* to collide.
 */
internal object MapRecordConverter : ResultConverter<PgRecord, Map<Any, Any?>> {

    override val supportedSourceClass = PgRecord::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Map::class || kClass == Any::class
    }

    override fun convert(source: PgRecord, expectedType: KType, sourceType: PgType, context: DeserializationContext): Map<Any, Any?> {
        // Arguments are only there to read on a `Map`; under `Any` there is nothing said about either half,
        // and a star-projected `Map<*, *>` says as little, so both fall back to an unconstrained type and the
        // chain answers as it answers anything asked for as `Any`.
        val asMap = expectedType.classifier == Map::class
        val keyType = (if (asMap) expectedType.arguments.getOrNull(0)?.type else null) ?: typeOf<Any>()
        val valueType = (if (asMap) expectedType.arguments.getOrNull(1)?.type else null) ?: typeOf<Any?>()

        if (source.fields.size % 2 != 0) throw MappingException(
            MappingExceptionReason.CONVERSION_ERROR,
            details = "Record fields must be in key-value pairs (even number of fields expected), " +
                "got ${source.fields.size}"
        )

        val result = LinkedHashMap<Any, Any?>(source.fields.size)

        for (i in source.fields.indices step 2) {
            // A key is named by its position, the record having no names to give - the same `[i]` the array
            // converter and `PgRecord.get` write. A value is named by the key just read, which is what a
            // caller holding the map has to go on.
            val keyRaw = source.fields[i] ?: throw conversionErrorAt(
                "[$i]",
                "A record read as a map cannot carry a NULL key: the field at index $i is null"
            )

            val key: Any = context.convert(keyRaw, keyType, source.getAttributeOid(i), "[$i]")

            if (result.containsKey(key)) throw conversionErrorAt(
                "[$i]",
                "Duplicate key '$key': the field at index ${i + 1} would replace the one already read under it"
            )

            val valueRaw = source.fields[i + 1]
            result[key] = if (valueRaw == null) {
                null
            } else {
                context.convert(valueRaw, valueType, source.getAttributeOid(i + 1), key.toString())
            }
        }

        return result
    }
}
