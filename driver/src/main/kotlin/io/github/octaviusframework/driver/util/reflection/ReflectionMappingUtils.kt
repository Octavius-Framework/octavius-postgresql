package io.github.octaviusframework.driver.util.reflection

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType

/**
 * Token used to represent a missing value during instantiation,
 * avoiding the need for allocating result wrappers like Result or Optional.
 */
object MissingToken

/**
 * Instantiates a data class based on its metadata.
 *
 * A missing value and a SQL `NULL` are two different things here, and [resolveValue] distinguishes them
 * by returning [MissingToken] for the first and `null` for the second:
 *
 * - **Absent** — a declared default covers it. Without a default, a nullable property is set to `null`
 *   and a non-nullable one raises `REQUIRED_ATTRIBUTE_MISSING`.
 * - **Present and `NULL`** — a value, so a default never replaces it. It reaches a nullable property as
 *   `null` and raises `REQUIRED_ATTRIBUTE_MISSING` for a non-nullable one, default or not.
 *
 * @param kClass The data class to instantiate; named in the exception message only.
 * @param metadata Pre-computed constructor metadata, from [ReflectionCache.getOrCreateDataObjectMetadata].
 * @param resolveValue Supplies the value for one constructor parameter, or [MissingToken] when the source
 *   holds nothing under that name.
 * @return The instantiated object.
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
 * Converts a map to a data class instance.
 *
 * Keys are matched to properties by converting each property name from `camelCase` to `snake_case`;
 * [PgName][io.github.octaviusframework.annotation.PgName] overrides that per property. A key the
 * class has no property for is ignored. A key that is absent and one whose value is `null` are treated
 * differently — see [instantiateDataObject].
 *
 * @param T The data class to build.
 * @return The instantiated object.
 * @throws MappingException if a value does not match its property's type, or a non-nullable property has
 *   no value to take. The property name is added to the exception's `path`.
 */
inline fun <reified T : Any> Map<String, Any?>.toDataObject(): T {
    return toDataObject(T::class)
}

/**
 * Converts a map to a data class instance.
 *
 * Keys are matched to properties by converting each property name from `camelCase` to `snake_case`;
 * [PgName][io.github.octaviusframework.annotation.PgName] overrides that per property. A key the
 * class has no property for is ignored. A key that is absent and one whose value is `null` are treated
 * differently — see [instantiateDataObject].
 *
 * @param kClass The data class to build.
 * @return The instantiated object.
 * @throws MappingException if a value does not match its property's type, or a non-nullable property has
 *   no value to take. The property name is added to the exception's `path`.
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

/**
 * Converts a data class object to a map, where keys are mapped property names
 * and values are the property values.
 *
 * Only primary constructor properties are included, under the same mapped names [toDataObject] reads.
 *
 * @param excludeKeys Mapped key names to exclude from the resulting map.
 * @return A map of mapped property name to property value.
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

/**
 * Validates whether a runtime value matches the expected Kotlin type.
 *
 * Used during object mapping to ensure type safety. Collections are checked shallowly: a `List` or
 * `Map` is validated against its first non-null element or entry only, so a heterogeneous collection
 * can pass. Generic type arguments that are not concrete classes are not checked at all.
 *
 * @param value The value to validate (can be null).
 * @param targetType The expected Kotlin type (KType) including generic parameters.
 * @return The original value if validation passes.
 * @throws MappingException `REQUIRED_ATTRIBUTE_MISSING` if [value] is null and [targetType] is not nullable,
 *   `CONVERSION_ERROR` if the value's type doesn't match the target type.
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
