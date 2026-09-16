package io.github.octaviusframework.driver.converter.result.mapper

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.registry.TypeLookup
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Entry point of the read conversion chain: hands a decoded value to the converters and checks the result.
 *
 * One of these belongs to each execution, over the [TypeCatalog] that execution pinned and whatever converters
 * the query registered for itself. It is what [Row.get][io.github.octaviusframework.driver.row.Row.get] and the
 * `fetchObject*` family call, and a `Row` holds on to the one its execution ran under - so a row read long after
 * the query finished still resolves against the catalog the query ran against.
 *
 * @param catalog The pinned catalog to resolve converters, dictionaries and registrations against.
 * @param localConverters Converters registered on the query itself, in registration order, or `null` where it
 *   registered none. Consulted ahead of the catalog's.
 * @param types The lookup handed to converters, reading the same pinned catalog.
 */
internal class ResultMapper(
    internal val catalog: TypeCatalog,
    localConverters: List<ResultConverter<*, *>>?,
    types: TypeLookup
) {
    internal val context = DefaultDeserializationContext(catalog, localConverters, types)

    /**
     * Converts a decoded database value to [expectedType].
     *
     * A `null` source is returned as `null` when [expectedType] allows it. Where no converter claims the
     * value, it is returned unchanged if it is already an instance of [expectedType]; otherwise this
     * fails rather than casting blindly. What a converter produces is checked against [expectedType]
     * too, so a converter that over-claims is named in the exception instead of surfacing as a
     * `ClassCastException` in the caller's own frame.
     *
     * @param T The type to return.
     * @param source The decoded value, or `null` for SQL `NULL`.
     * @param expectedType The Kotlin type wanted, generic arguments included.
     * @param sourceType The PostgreSQL type of the value.
     * @return The converted value.
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

            // The cast below is erased, so a converter that answered canConvert() and then produced
            // something else would sail through here and blow up as a ClassCastException in the
            // caller's own frame - with nothing in the stack naming the converter responsible.
            // Checking here is what turns that into an exception that can say who did it.
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
     * The query's own converters first, then the catalog's, and within each the class the codec produced
     * before `Any::class` - so a converter registered on the query displaces one the session registered for
     * the same class, and a catch-all registered on the query displaces both.
     */
    @Suppress("UNCHECKED_CAST")
    override fun findConverter(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType): ResultConverter<Any, *>? {
        val local = localConverters
        if (local != null) {
            val anyClass: KClass<*> = Any::class
            val lastIndex = local.size - 1
            for (pass in 0..1) {
                val wanted = if (pass == 0) sourceClass else anyClass
                // Registration order, so the most recent is asked first.
                for (i in lastIndex downTo 0) {
                    val converter = local[i]
                    if (converter.supportedSourceClass != wanted) continue
                    converter as ResultConverter<Any, *>
                    if (converter.canConvert(sourceClass, expectedType, sourceType, this)) return converter
                }
            }
        }
        return catalog.findResultConverter(sourceClass, expectedType, sourceType, this)
    }
}
