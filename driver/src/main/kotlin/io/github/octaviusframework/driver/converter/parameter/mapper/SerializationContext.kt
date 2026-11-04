package io.github.octaviusframework.driver.converter.parameter.mapper

import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.type.UNRESOLVED_OID
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

/** What a [ParameterConverter] converts a nested value through, where that value's codec does not take it as it is. */
interface SerializationContext {
    /** The catalog this execution pinned, read-only. */
    val types: TypeLookup

    /**
     * Converts [source] for [expectedOid] through the registered converters.
     *
     * @param pathSegment Added to the path of a failure: a property name, or `[i]`.
     */
    fun convert(source: Any, expectedOid: Int, pathSegment: String? = null): Any?

    /**
     * Returns the converter that would claim [source] for [expectedOid], or `null` if none would. Pass `0` if the OID
     * is not known.
     */
    fun findConverter(source: Any, expectedOid: Int): ParameterConverter<Any>?

    /** [findConverter] for a class rather than an instance. */
    fun findConverterByClass(sourceClass: KClass<*>, expectedOid: Int): ParameterConverter<Any>?

    /**
     * The OID a value of [kClass] would go out as, for an empty container with no element to ask: the type its
     * converter names, else its codec's, else `null`.
     */
    fun defaultOidForClass(kClass: KClass<*>): Int? {
        findConverterByClass(kClass, UNRESOLVED_OID)?.getDefaultTypeName(kClass, this)
            ?.let { types.resolveOid(it.name, it.schema, it.isArray) }
            ?.takeIf { it.isKnownOid }
            ?.let { return it }

        val codec = types.codecs.getCodecByClass(kClass) ?: return null
        return types.codecs.getOidForCodec(codec) ?: types.resolveOid(codec.pgTypeName, codec.pgSchema)
    }
}

