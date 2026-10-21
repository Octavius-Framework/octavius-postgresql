package io.github.octaviusframework.driver.util.reflection

import io.github.octaviusframework.annotation.PgName
import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.identifier.CaseConverter
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.*
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Metadata for a single constructor parameter and its corresponding property in a data class.
 *
 * @param T The type of the data class.
 * @property parameter The reflection [KParameter] object from the constructor.
 * @property property The reflection [KProperty1] object corresponding to the parameter.
 * @property type The Kotlin [KType] of the parameter.
 * @property keyName The mapped name used to resolve this property against a PostgreSQL record/composite type.
 */
data class ConstructorParamMetadata<T : Any>(
    val parameter: KParameter,
    val property: KProperty1<T, Any?>,
    val type: KType,
    val keyName: String
)

/**
 * Pre-computed reflection metadata for a data class.
 *
 * @param T The type of the data class.
 * @property constructor The primary constructor of the data class.
 * @property constructorProperties A list of metadata for each constructor parameter.
 */
data class DataObjectClassMetadata<T : Any>(
    val constructor: KFunction<T>,
    val constructorProperties: List<ConstructorParamMetadata<T>>
) {
    /**
     * [constructorProperties] by [keyName][ConstructorParamMetadata.keyName], built once with the rest of the
     * metadata rather than per object written - writing a composite looks every attribute up by its name.
     */
    internal val propertiesByKeyName: Map<String, ConstructorParamMetadata<T>> =
        constructorProperties.associateBy { it.keyName }
}

/**
 * A global cache for Kotlin reflection metadata of data classes.
 *
 * This prevents the expensive overhead of inspecting class properties and annotations
 * repeatedly during the serialization or deserialization of PostgreSQL composite types.
 */
object ReflectionCache {
    private val dataObjectCache = ConcurrentHashMap<KClass<*>, DataObjectClassMetadata<*>>()

    /**
     * Returns the cached metadata for [kClass], computing it on first request.
     *
     * The cache is global and never evicted, which is what makes registering a composite type up front
     * enough to keep reflection off the query path entirely. Mapped key names are resolved here, once:
     * a property's [PgName][io.github.octaviusframework.annotation.PgName] if it carries one,
     * otherwise its name converted from `camelCase` to `snake_case`.
     *
     * @param T The data class to inspect.
     * @param kClass Its [KClass].
     * @return The metadata, shared with every other caller for the same class.
     * @throws IllegalArgumentException if [kClass] has no primary constructor.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrCreateDataObjectMetadata(
        kClass: KClass<T>
    ): DataObjectClassMetadata<T> {
        return dataObjectCache.getOrPut(kClass) {
            val constructor = kClass.primaryConstructor
                ?: throw IllegalArgumentException("Class ${kClass.simpleName} must have a primary constructor.")

            val propertiesByName = kClass.memberProperties.associateBy { it.name }

            val constructorProperties = constructor.parameters.map { param ->
                val property = propertiesByName[param.name]!!

                val keyName = property.findAnnotation<PgName>()?.name
                    ?: CaseConverter.convert(param.name!!, CaseConvention.CAMEL_CASE, CaseConvention.SNAKE_CASE_LOWER)

                ConstructorParamMetadata(
                    parameter = param,
                    property = property,
                    type = param.type,
                    keyName = keyName
                )
            }
            DataObjectClassMetadata(constructor, constructorProperties)
        } as DataObjectClassMetadata<T>
    }
}
