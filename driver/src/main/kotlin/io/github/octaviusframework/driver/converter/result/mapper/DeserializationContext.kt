package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeLookup
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What a [ResultConverter] is handed to convert the values nested inside the one it was given.
 *
 * Going through [convert] rather than converting a nested value by hand is what keeps the failure
 * legible: each call contributes a segment to the `path` of any `MappingException` raised below it,
 * so an error deep in a composite arrives naming the attribute it happened in.
 */
interface DeserializationContext {
    /**
     * The type system as this execution pinned it: dictionaries, containers and OID resolution.
     *
     * Reading only - a conversion cannot register a type, a codec or a converter, and would have nothing to
     * gain by it, since the catalog it is running against was fixed before the statement went out.
     */
    val types: TypeLookup

    /**
     * Converts a raw value to the expected type using registered converters.
     *
     * @param source The raw value to convert.
     * @param expectedType The Kotlin type expected as the result.
     * @param sourceType The PostgreSQL type of the raw value.
     * @param pathSegment Optional segment name (e.g., property name or array index) for debugging purposes. 
     * If a MappingException occurs during conversion, this segment is added to the exception's path
     * to help trace the exact location of the error in nested structures.
     * @return The converted value.
     */
    fun <T> convert(source: Any?, expectedType: KType, sourceType: PgType, pathSegment: String? = null): T

    /**
     * Looks up the converter that would claim a value, without converting anything.
     *
     * @param sourceClass The class of the decoded value.
     * @param expectedType The Kotlin type wanted.
     * @param sourceType The PostgreSQL type of the value.
     * @return The converter that would be used, or `null` if none claims it.
     */
    fun findConverter(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType): ResultConverter<Any, *>?

    /**
     * Converts a raw value to the expected type using registered converters, resolving the PostgreSQL type from its OID.
     *
     * @param source The raw value to convert.
     * @param expectedType The Kotlin type expected as the result.
     * @param sourceOid The PostgreSQL OID of the raw value.
     * @param pathSegment Optional segment name (e.g., property name or array index) for debugging purposes.
     * If a MappingException occurs during conversion, this segment is added to the exception's path
     * to help trace the exact location of the error in nested structures.
     * @return The converted value.
     * @throws io.github.octaviusframework.driver.exception.TypeException if [sourceOid] names no known type.
     */
    fun <T> convert(source: Any?, expectedType: KType, sourceOid: Int, pathSegment: String? = null): T {
        return convert(source, expectedType, types.dictionary.getPgType(sourceOid), pathSegment)
    }
}
