package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.util.reflection.sealedFamily
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Hands a decoded value to the converters and checks what comes back. One per execution, over its pinned
 * [catalog]; a `Row` keeps the one it was read under.
 *
 * @param localConverters The query's own converters, in registration order, asked ahead of the catalog's.
 */
internal class ResultMapper(
    internal val catalog: TypeCatalog,
    localConverters: List<ResultConverter<*, *>>?,
    types: TypeLookup
) {
    internal val context = DefaultDeserializationContext(catalog, localConverters, types)

    /**
     * Converts [source] to [expectedType]. A value no converter claims passes only if it already is one.
     *
     * @throws MappingException
     *   `REQUIRED_ATTRIBUTE_MISSING` if [source] is `null` and [expectedType] is not nullable,
     *   `NO_CONVERTER_FOUND` if nothing can produce [expectedType],
     *   `CONVERSION_ERROR` if a converter failed or returned the wrong type.
     */
    fun <T> deserialize(source: Any?, expectedType: KType, sourceType: PgType): T {
        return context.convert(source, expectedType, sourceType)
    }
}

internal class DefaultDeserializationContext(
    private val catalog: TypeCatalog,
    private val localConverters: List<ResultConverter<*, *>>?,
    override val types: TypeLookup
) : DeserializationContext {
    /** The classes each of [localConverters] is offered values of, as the catalog indexes its own. */
    private val localSourceClasses = localConverters?.map { it.supportedSourceClass.sealedFamily() }

    override fun <T> convert(source: Any?, expectedType: KType, sourceType: PgType, pathSegment: String?): T {
        try {
        if (source == null) {
            if (!expectedType.isMarkedNullable) {
                throw MappingException(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, "Cannot deserialize null to non-nullable type $expectedType")
            }
            @Suppress("UNCHECKED_CAST")
            return null as T
        }

        val kClass = expectedType.classifier as? KClass<*>

        val converter = findConverter(source::class, expectedType, sourceType)
        if (converter != null) {
            val converted = converter.convert(source, expectedType, sourceType, this)

            // The cast below is erased; without this a wrong result surfaces as a ClassCastException in the
            // caller's frame, naming no converter.
            if (kClass != null && !kClass.isInstance(converted)) {
                throw MappingException(
                    MappingExceptionReason.CONVERSION_ERROR,
                    details = "Converter ${converter::class.qualifiedName ?: converter::class} returned " +
                            "${converted::class.qualifiedName ?: converted::class} but $expectedType was expected. " +
                            "A converter whose canConvert() accepts more than it can produce is the usual cause."
                )
            }

            @Suppress("UNCHECKED_CAST")
            return converted as T
        }

        // Fallback: if the source is already of the appropriate type, just cast it
        // np. String -> String
        if (kClass != null && kClass.isInstance(source)) {
            @Suppress("UNCHECKED_CAST")
            return source as T
        }

            throw MappingException(MappingExceptionReason.NO_CONVERTER_FOUND, "No converter found for source ${source::class} and expected type $expectedType")
        } catch (e: MappingException) {
            if (pathSegment != null) e.path.add(pathSegment)
            throw e
        } catch (e: Exception) {
            val ex = MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "Error during result deserialization: ${e.message}",
                cause = e
            )
            if (pathSegment != null) ex.path.add(pathSegment)
            throw ex
        }
    }

    /**
     * The query's converters, then the catalog's; within each, those of the value's class or a sealed class
     * above it before those of `Any::class`.
     */
    @Suppress("UNCHECKED_CAST")
    override fun findConverter(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType): ResultConverter<Any, *>? {
        val local = localConverters
        val localClasses = localSourceClasses
        if (local != null && localClasses != null) {
            val anyClass: KClass<*> = Any::class
            val lastIndex = local.size - 1
            for (pass in 0..1) {
                // Registration order, so the most recent is asked first.
                for (i in lastIndex downTo 0) {
                    val converter = local[i]
                    val offered = if (pass == 0) sourceClass in localClasses[i] else converter.supportedSourceClass == anyClass
                    if (!offered) continue
                    converter as ResultConverter<Any, *>
                    if (converter.canConvert(sourceClass, expectedType, sourceType, this)) return converter
                }
            }
        }
        return catalog.findResultConverter(sourceClass, expectedType, sourceType, this)
    }
}
