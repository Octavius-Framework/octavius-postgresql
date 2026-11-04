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
 * A data class constructor parameter and its property.
 *
 * @property keyName The attribute or column name it maps to.
 */
data class ConstructorParamMetadata<T : Any>(
    val parameter: KParameter,
    val property: KProperty1<T, Any?>,
    val type: KType,
    val keyName: String
)

/** A data class's primary constructor and its parameters, read once. */
data class DataObjectClassMetadata<T : Any>(
    val constructor: KFunction<T>,
    val constructorProperties: List<ConstructorParamMetadata<T>>
) {
    /** [constructorProperties] by [keyName][ConstructorParamMetadata.keyName]. */
    internal val propertiesByKeyName: Map<String, ConstructorParamMetadata<T>> =
        constructorProperties.associateBy { it.keyName }
}

/** The reflection metadata of data classes, read once per class for the JVM. */
object ReflectionCache {
    private val dataObjectCache = ConcurrentHashMap<KClass<*>, DataObjectClassMetadata<*>>()

    /**
     * The metadata of [kClass], read on first request and never evicted. A property maps to its
     * [PgName][io.github.octaviusframework.annotation.PgName], or its name in `snake_case`.
     *
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
