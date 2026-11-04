package io.github.octaviusframework.driver.converter.parameter.mapper

import io.github.octaviusframework.driver.identifier.QualifiedName
import kotlin.reflect.KClass

/**
 * Turns a Kotlin value into one a codec encodes - a `String` for an enum label, a `PgComposite` for a data class.
 * Register one with
 * [TypeManager.registerParameterConverter][io.github.octaviusframework.driver.registry.TypeManager.registerParameterConverter]
 * or on one query.
 *
 * The newest registration is asked first. A value nothing claims goes to the codec as it is, and one that codec
 * cannot take raises `MappingException` (`NO_CONVERTER_FOUND`).
 *
 * @param T The Kotlin type this converter accepts.
 */
interface ParameterConverter<T : Any> {
    /** The Kotlin class this converter handles, subtypes included under the default [canConvert]. */
    val supportedClass: KClass<T>

    /**
     * Whether this converter takes a value of [sourceClass] going out as [expectedOid], which is `0` if it is not
     * known. The default takes [supportedClass] and its subtypes whatever the OID.
     */
    fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        return supportedClass == sourceClass || supportedClass.java.isAssignableFrom(sourceClass.java)
    }

    /** Converts a claimed value into one a codec encodes. */
    fun convert(source: T, expectedOid: Int, context: SerializationContext): Any

    /**
     * The type to declare a parameter as where nothing names one, or `null` to take it from the converted value's
     * class. An enum converter returns a `String`, which would otherwise go out as `text`.
     */
    fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext): QualifiedName? = null
}

