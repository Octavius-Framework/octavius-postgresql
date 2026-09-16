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
 * Entry point of the write conversion chain: hands a Kotlin value to the converters.
 *
 * One of these belongs to each execution, over the [TypeCatalog] that execution pinned and whatever converters
 * the query registered for itself. It is what the parameter serializer calls for every value bound to a
 * statement.
 *
 * @param catalog The pinned catalog to resolve converters and codecs against.
 * @param localConverters Converters registered on the query itself, in registration order, or `null` where it
 *   registered none. Consulted ahead of the catalog's.
 * @param types The lookup handed to converters, reading the same pinned catalog.
 */
internal class ParameterMapper(
    catalog: TypeCatalog,
    localConverters: List<ParameterConverter<*>>?,
    types: TypeLookup
) {
    private val context = DefaultSerializationContext(catalog, localConverters, types)

    /**
     * Converts a Kotlin value into something a codec can encode.
     *
     * @param source The value being sent; `null` passes straight through.
     * @param expectedOid The OID the server expects, or `0` when it is not known.
     * @return A value a registered codec can encode, or `null`.
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
     * Runs a value through the first converter that claims it.
     *
     * Where the target OID was not known and the converter named a default type for the value, the
     * result is wrapped in [PgTyped] so the type reaches the server with it.
     *
     * A value nothing claims is returned untouched - correct for a scalar the codec already accepts.
     * Where the target OID is known and its codec cannot accept that class, nothing downstream can
     * either, so this fails here rather than one layer down as an encoding error: failing at this point
     * is what keeps the attribute name or element index in the exception's `path`, since by the time the
     * codec runs the structure the value sat in is gone.
     */
    override fun convert(source: Any, expectedOid: Int, pathSegment: String?): Any {
        try {
            val converter = findConverterByClass(source::class, expectedOid)
            if (converter != null) {
                var result = converter.convert(source, expectedOid, this)
                // A PgContainer already carries its own OID, so wrapping it would only force
                // the serializer to resolve a name it then discards in favour of containerOid.
                if (result !is PgTyped && result !is PgContainer && !expectedOid.isKnownOid) {
                    val defaultType = converter.getDefaultTypeName(source::class, this)
                    if (defaultType != null) {
                        result = PgTyped(result, defaultType)
                    }
                }
                return result
            }

            // Nothing claimed the value. Passing it through untouched is right for a scalar the codec takes as it
            // stands, but where the target OID is known and its codec cannot accept this class, nothing downstream
            // can either. Failing here is what keeps the attribute name in the path - by the time the codec runs,
            // the structure the value sat in is gone. The read direction reports the same mistake the same way,
            // as MappingException(NO_CONVERTER_FOUND).
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

    /** The query's own converters first, most recently registered first, then the catalog's. */
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
