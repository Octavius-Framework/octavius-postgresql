package io.github.octaviusframework.driver.util.reflection

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

/** What a value resolver returns for a name the source does not hold, as opposed to a `NULL`. */
object MissingToken

/**
 * Builds a data class from [metadata], each parameter's value from [resolveValue]. A [MissingToken] takes the
 * declared default, or `null`; a `NULL` is a value, which a default never replaces.
 *
 * @param kClass Named in the exception only.
 * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if a non-nullable property has no value to take.
 */
fun <T : Any> instantiateDataObject(
    kClass: KClass<T>,
    metadata: DataObjectClassMetadata<T>,
    resolveValue: (ConstructorParamMetadata<T>) -> Any?
): T {
    val properties = metadata.constructorProperties
    // In constructor order, MissingToken standing for a parameter its declared default is to cover.
    val args = arrayOfNulls<Any?>(properties.size)
    var defaultsNeeded = false

    for (i in properties.indices) {
        val meta = properties[i]
        val param = meta.parameter

        when (val result = resolveValue(meta)) {
            // Nothing in the result set under this name: the default is what covers it.
            MissingToken -> {
                if (param.isOptional) {
                    args[i] = MissingToken
                    defaultsNeeded = true
                } else if (!meta.type.isMarkedNullable) {
                    throw MappingException(
                        MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING,
                        "Missing non-nullable attribute '${meta.keyName}' for $kClass",
                        path = mutableListOf(meta.keyName)
                    )
                }
            }
            // Present and NULL: a value
            null -> {
                if (!meta.type.isMarkedNullable) {
                    throw MappingException(
                        MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING,
                        "Null value for non-nullable attribute '${meta.keyName}' for $kClass",
                        path = mutableListOf(meta.keyName)
                    )
                }
            }
            else -> args[i] = result
        }
    }

    if (!defaultsNeeded) return metadata.constructor.call(*args)

    // Only callBy can leave a parameter out for its default to fill, and it takes the arguments by parameter.
    val byParameter = HashMap<KParameter, Any?>(properties.size * 2)
    for (i in properties.indices) {
        if (args[i] !== MissingToken) byParameter[properties[i].parameter] = args[i]
    }
    return metadata.constructor.callBy(byParameter)
}

/**
 * This data class's constructor properties by their `snake_case` names or their
 * [PgName][io.github.octaviusframework.annotation.PgName], without the keys in [excludeKeys].
 *
 * @throws InvalidOperationException `INVALID_ARGUMENT` if the class is not a data class.
 */
fun <T : Any> T.toDataMap(
    vararg excludeKeys: String
): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val kClass = this::class as KClass<T>
    val metadata = ReflectionCache.getOrCreateDataObjectMetadata(kClass)
    
    val exclusionSet = if (excludeKeys.isNotEmpty()) excludeKeys.toSet() else emptySet()

    return metadata.constructorProperties.mapNotNull { meta ->
        val keyName = meta.keyName
        if (keyName in exclusionSet) {
            return@mapNotNull null
        }
        val value = meta.property.get(this)
        
        keyName to value
    }.associate { it }
}
