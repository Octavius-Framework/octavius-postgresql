package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeLookup
import kotlin.reflect.KClass
import kotlin.reflect.KType

/** What a [ResultConverter] converts a nested value through, where the decoded value is not yet of the type needed. */
interface DeserializationContext {
    /** The catalog this execution pinned, read-only. */
    val types: TypeLookup

    /**
     * Converts [source], a value of [sourceType], into [expectedType] through the registered converters.
     *
     * @param pathSegment Added to the path of a failure: a property name, or `[i]`.
     */
    fun <T> convert(source: Any?, expectedType: KType, sourceType: PgType, pathSegment: String? = null): T

    /** Returns the converter that would claim a value of [sourceClass] as [expectedType], or `null` if none would. */
    fun findConverter(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType): ResultConverter<Any, *>?

    /**
     * [convert] for a value given by its type's OID.
     *
     * @throws io.github.octaviusframework.driver.exception.TypeException if [sourceOid] names no known type.
     */
    fun <T> convert(source: Any?, expectedType: KType, sourceOid: Int, pathSegment: String? = null): T {
        return convert(source, expectedType, types.dictionary.getPgType(sourceOid), pathSegment)
    }
}
