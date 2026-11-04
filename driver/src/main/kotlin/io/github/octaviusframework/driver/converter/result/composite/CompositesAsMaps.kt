package io.github.octaviusframework.driver.converter.result.composite

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Returns a converter that reads every composite asked for as `Any` or `Map` into a `Map<String, Any?>`, nested ones
 * included, whatever class it is registered as. A class asked for by name is still that class.
 *
 * ```kotlin
 * session.createNativeQuery("SELECT * FROM veterans")
 *     .registerResultConverter(compositesAsMaps())
 *     .fetchObjects<Map<String, Any?>>()
 * ```
 */
fun compositesAsMaps(): ResultConverter<PgComposite, Map<String, Any?>> = CompositesAsMapsConverter(null)

/**
 * [compositesAsMaps] for the composite [name] alone, wherever it is nested; other composites read as registered.
 *
 * ```kotlin
 * session.createNativeQuery("SELECT assessment FROM censuses")
 *     .registerResultConverter(compositesAsMaps("tribute"))
 *     .fetchField<Map<String, Any?>>()      // {label=census, payload={amount=40, currency=denarius}}
 * ```
 *
 * @param schema Empty to match the name in any schema.
 */
fun compositesAsMaps(name: String, schema: String = ""): ResultConverter<PgComposite, Map<String, Any?>> =
    CompositesAsMapsConverter(arrayOf(QualifiedName(schema, name)))

/**
 * [compositesAsMaps] for several [types], as one converter rather than one apiece. An empty schema matches the
 * name in any schema.
 */
fun compositesAsMaps(types: Collection<QualifiedName>): ResultConverter<PgComposite, Map<String, Any?>> =
    CompositesAsMapsConverter(types.toTypedArray())

/**
 * @param only The types to collapse, or `null` to collapse every composite.
 */
private class CompositesAsMapsConverter(
    private val only: Array<QualifiedName>?
) : ResultConverter<PgComposite, Map<String, Any?>> {

    override val supportedSourceClass = PgComposite::class

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        // The name first: it is cheap and usually declines, while `expectedType.classifier` is reflective.
        val targets = only
        if (targets != null && !names(targets, sourceType)) return false

        // Anything narrower was asked for by name.
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Any::class || kClass == Map::class
    }

    /** Whether any of [targets] is [sourceType], an empty schema on a target matching whichever it came from. */
    private fun names(targets: Array<QualifiedName>, sourceType: PgType): Boolean {
        for (i in targets.indices) {
            val target = targets[i]
            if (sourceType.name != target.name) continue
            if (target.schema.isEmpty() || sourceType.schema == target.schema) return true
        }
        return false
    }

    override fun convert(source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext): Map<String, Any?> {
        // `Map<String, Address>` asks for `Address`; only `Any` recurses through this converter.
        val valueType = if (expectedType.classifier == Map::class) {
            expectedType.arguments.getOrNull(1)?.type ?: typeOf<Any?>()
        } else {
            typeOf<Any?>()
        }

        return source.attributesAsMap(valueType, context)
    }
}
