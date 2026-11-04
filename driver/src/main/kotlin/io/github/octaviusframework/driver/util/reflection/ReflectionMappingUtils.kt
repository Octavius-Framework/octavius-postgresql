package io.github.octaviusframework.driver.util.reflection

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType

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
 * This map as the data class [T], each property read from the key of its `snake_case` name or its
 * [PgName][io.github.octaviusframework.annotation.PgName]. An absent key and a `null` differ as in
 * [instantiateDataObject]; a key with no property is ignored.
 *
 * @throws MappingException if a value does not fit its property, or a non-nullable property has none.
 */
inline fun <reified T : Any> Map<String, Any?>.toDataObject(): T {
    return toDataObject(T::class)
}

/**
 * [toDataObject] for a class found at runtime.
 *
 * @throws MappingException if a value does not fit its property, or a non-nullable property has none.
 */
fun <T : Any> Map<String, Any?>.toDataObject(
    kClass: KClass<T>
): T {
    val metadata = ReflectionCache.getOrCreateDataObjectMetadata(kClass)
    
    return instantiateDataObject(kClass, metadata) { meta ->
        if (this.containsKey(meta.keyName)) {
            val value = this[meta.keyName]
            try {
                validateValue(value, meta.type)
            } catch (e: MappingException) {
                e.path.add(meta.keyName)
                throw e
            }
        } else {
            MissingToken
        }
    }
}

/** This data class's constructor properties by the names [toDataObject] reads, without [excludeKeys]. */
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

/**
 * Returns [value] if it fits [targetType]. A `List` or `Map` is checked by its first non-null element only.
 *
 * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [value] is null and [targetType] is not nullable,
 *   `CONVERSION_ERROR` if it does not fit.
 */
private fun validateValue(value: Any?, targetType: KType): Any? {
    if (value == null) {
        if (targetType.isMarkedNullable) {
            return null
        } else {
            throw MappingException(
                reason = MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING,
                details = "Null value for non-nullable expected type $targetType"
            )
        }
    }

    val classifier = targetType.classifier
    if (classifier is kotlin.reflect.KTypeParameter) {
        throw MappingException(
            reason = MappingExceptionReason.CONVERSION_ERROR,
            details = "Unsupported generic type parameter in data class: $targetType"
        )
    }

    val targetClass = classifier as? KClass<*> ?: return value

    // --- Validation 1: Check main type ---
    if (!targetClass.isInstance(value)) {
        throw MappingException(
            reason = MappingExceptionReason.CONVERSION_ERROR,
            details = "Incompatible type. Expected $targetType but got ${value::class}"
        )
    }

    // --- Validation 2: Check element type in collection ---
    when (value) {
        is List<*> -> validateList(value, targetType)
        is Map<*, *> -> validateMap(value, targetType)
    }

    return value
}

private fun validateList(value: List<*>, targetType: KType) {
    val firstNonNullElement = value.firstOrNull { it != null }

    if (firstNonNullElement != null) {
        val listElementType = targetType.arguments.firstOrNull()?.type ?: return
        val listElementClass = listElementType.classifier as? KClass<*> ?: return

        if (!listElementClass.isInstance(firstNonNullElement)) {
            throw MappingException(
                reason = MappingExceptionReason.CONVERSION_ERROR,
                details = "Incompatible collection element type. Expected $listElementType but got ${firstNonNullElement::class}"
            )
        }
    }
}

private fun validateMap(value: Map<*, *>, targetType: KType) {
    val firstNonNullEntry = value.entries.firstOrNull { it.key != null && it.value != null }

    if (firstNonNullEntry != null && targetType.arguments.size == 2) {
        val keyType = targetType.arguments[0].type ?: return
        val valueType = targetType.arguments[1].type ?: return

        val keyClass = keyType.classifier as? KClass<*> ?: return
        val valueClass = valueType.classifier as? KClass<*> ?: return

        if (!keyClass.isInstance(firstNonNullEntry.key) || !valueClass.isInstance(firstNonNullEntry.value)) {
            throw MappingException(
                reason = MappingExceptionReason.CONVERSION_ERROR,
                details = "Incompatible map entry types for map $targetType. Key: ${firstNonNullEntry.key!!::class}, Value: ${firstNonNullEntry.value!!::class}"
            )
        }
    }
}
