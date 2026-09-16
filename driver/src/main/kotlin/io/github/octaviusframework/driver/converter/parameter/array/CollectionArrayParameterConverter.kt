package io.github.octaviusframework.driver.converter.parameter.array

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.PgTyped
import io.github.octaviusframework.driver.type.UNRESOLVED_OID
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object CollectionArrayParameterConverter : ParameterConverter<Any> {

    override val supportedClass: KClass<Any> = Any::class

    override fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        return Collection::class.java.isAssignableFrom(sourceClass.java) ||
               (sourceClass.java.isArray && sourceClass.java.componentType?.isPrimitive == false)
    }

    private fun getDimensions(source: Any): List<ArrayDimension> {
        val dimensions = mutableListOf<Int>()
        var current: Any? = source

        while (current is Collection<*> || current is Array<*>) {
            val size = if (current is Collection<*>) current.size else (current as Array<*>).size
            dimensions.add(size)
            current = if (current is Collection<*>) current.firstOrNull() else (current as Array<*>).firstOrNull()
        }

        return dimensions.map { ArrayDimension(it, 1) }
    }

    private fun findFirstNonNull(source: Any): Any? {
        when (source) {
            is Collection<*> -> {
                for (item in source) {
                    val found = findFirstNonNull(item ?: continue)
                    if (found != null) return found
                }
            }
            is Array<*> -> {
                for (item in source) {
                    val found = findFirstNonNull(item ?: continue)
                    if (found != null) return found
                }
            }
            else -> return source
        }
        return null
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {
        val dimensions = getDimensions(source)
        val expectedSize = dimensions.fold(1) { acc, dim -> acc * dim.size }

        val arrayType = if (expectedOid.isKnownOid) {
            context.types.dictionary.getPgType(expectedOid) as? PgType.Array
        } else {
            // Try to infer from first non-null element
            val firstNonNull = findFirstNonNull(source)
            if (firstNonNull != null) {
                val converted = context.convert(firstNonNull, UNRESOLVED_OID)
                val elementOid = when {
                    converted is PgTyped -> {
                        context.types.resolveOid(
                            converted.pgType.name,
                            converted.pgType.schema,
                            converted.pgType.isArray
                        )
                    }

                    converted is PgContainer -> {
                        converted.containerOid
                    }

                    converted != null -> {
                        context.types.codecs.getCodecByClass(converted::class)?.oid
                    }

                    else -> null
                }

                if (elementOid != null) {
                    context.types.dictionary.getArrayType(elementOid)
                } else null
            } else null
        }

        if (arrayType == null) {
            throw TypeException(
                TypeExceptionReason.TYPE_NOT_FOUND,
                details = "Cannot infer array type for the collection. The collection is empty, contains only nulls, or the element type is unknown. Use explicit typing (e.g. .withPgType(...))."
            )
        }

        val elementOid = arrayType.elementOid

        val convertedElements = ArrayList<Any?>(expectedSize)
        var globalIndex = 0

        fun flattenAndConvert(item: Any?) {
            when (item) {
                is Collection<*> -> {
                    for (child in item) flattenAndConvert(child)
                }
                is Array<*> -> {
                    for (child in item) flattenAndConvert(child)
                }
                else -> {
                    if (item != null) {
                        try {
                            convertedElements.add(context.convert(item, elementOid, null))
                        } catch (e: MappingException) {
                            e.path.add("[$globalIndex]")
                            throw e
                        }
                    } else {
                        convertedElements.add(null)
                    }
                    globalIndex++
                }
            }
        }

        flattenAndConvert(source)

        require(dimensions.isEmpty() || dimensions.first().size == 0 || convertedElements.size == expectedSize) { "Multidimensional arrays must be rectangular" }

        return PgArray(
            arrayOid = arrayType.oid,
            elementOid = elementOid,
            dimensions = dimensions,
            elements = convertedElements
        )
    }
}
