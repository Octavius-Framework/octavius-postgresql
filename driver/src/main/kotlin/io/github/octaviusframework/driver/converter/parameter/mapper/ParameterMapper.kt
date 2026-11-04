package io.github.octaviusframework.driver.converter.parameter.mapper

import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.type.PgTyped
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

/**
 * Hands each bound value to the converters. One per execution, over its pinned [catalog].
 *
 * @param localConverters The query's own converters, in registration order, asked ahead of the catalog's.
 */
internal class ParameterMapper(
    catalog: TypeCatalog,
    localConverters: List<ParameterConverter<*>>?,
    types: TypeLookup
) {
    private val context = DefaultSerializationContext(catalog, localConverters, types)

    /**
     * Converts [source] for [expectedOid] into a value a codec encodes. Pass `0` if the OID is not known.
     *
     * @throws MappingException
     *   `NO_CONVERTER_FOUND` if nothing claims the value and the target codec cannot accept its class,
     *   `CONVERSION_ERROR` if a converter failed.
     */
    fun convert(source: Any?, expectedOid: Int): Any? {
        if (source == null) return null
        return context.convert(source, expectedOid)
    }
}

internal class DefaultSerializationContext(
    private val catalog: TypeCatalog,
    private val localConverters: List<ParameterConverter<*>>?,
    override val types: TypeLookup
) : SerializationContext {
    /**
     * Runs [source] through the first converter that claims it, wrapping the result in [PgTyped] where the OID is
     * unknown and the converter names a type. A value nothing claims goes on as it is, unless the known OID's
     * codec cannot take it: that fails here, where the path still names it.
     */
    override fun convert(source: Any, expectedOid: Int, pathSegment: String?): Any {
        try {
            val converter = findConverterByClass(source::class, expectedOid)
            if (converter != null) {
                var result = converter.convert(source, expectedOid, this)
                // A PgContainer carries its own OID already.
                if (result !is PgTyped && result !is PgContainer && !expectedOid.isKnownOid) {
                    val defaultType = converter.getDefaultTypeName(source::class, this)
                    if (defaultType != null) {
                        result = PgTyped(result, defaultType)
                    }
                }
                return result
            }

            // Nothing claimed the value; fail here, while the path still names it, if its codec cannot take it.
            rejectIfCodecCannotAccept(source, expectedOid)
            return source
        } catch (e: MappingException) {
            if (pathSegment != null) e.path.add(pathSegment)
            throw e
        } catch (e: Exception) {
            val ex = MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "Error during parameter serialization: ${e.message}",
                cause = e
            )
            if (pathSegment != null) ex.path.add(pathSegment)
            throw ex
        }
    }

    private fun rejectIfCodecCannotAccept(source: Any, expectedOid: Int) {
        if (!expectedOid.isKnownOid) return
        val codec = catalog.codecs.getCodecByOid<Any>(expectedOid) ?: return
        if (codec.kotlinClass.isInstance(source)) return

        throw MappingException(
            MappingExceptionReason.NO_CONVERTER_FOUND,
            "No converter found for source class ${source::class.qualifiedName ?: source::class} and expected " +
                    "type ${codec.pgTypeName} (OID $expectedOid), which encodes " +
                    "${codec.kotlinClass.qualifiedName ?: codec.kotlinClass}"
        )
    }

    override fun findConverter(source: Any, expectedOid: Int): ParameterConverter<Any>? =
        findConverterByClass(source::class, expectedOid)

    /** The query's converters, newest first, then the catalog's. */
    @Suppress("UNCHECKED_CAST")
    override fun findConverterByClass(sourceClass: KClass<*>, expectedOid: Int): ParameterConverter<Any>? {
        val local = localConverters
        if (local != null) {
            for (i in local.indices.reversed()) {
                val converter = local[i]
                if (converter.canConvert(sourceClass, expectedOid, this)) return converter as ParameterConverter<Any>
            }
        }
        return catalog.findParameterConverter(sourceClass, expectedOid, this)
    }
}
