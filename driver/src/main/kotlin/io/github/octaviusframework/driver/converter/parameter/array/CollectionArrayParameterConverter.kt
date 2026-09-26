package io.github.octaviusframework.driver.converter.parameter.array

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
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

    /** The level below [item], or `null` where [item] is an element. A `ByteArray` is `bytea`, so an element. */
    private fun levelBelow(item: Any?): Collection<Any?>? = when (item) {
        is Collection<*> -> item
        is Array<*> -> item.asList()
        is ByteArray -> null
        is IntArray -> item.asList()
        is LongArray -> item.asList()
        is ShortArray -> item.asList()
        is DoubleArray -> item.asList()
        is FloatArray -> item.asList()
        is BooleanArray -> item.asList()
        is CharArray -> item.asList()
        else -> null
    }

    private fun getDimensions(source: Any): List<ArrayDimension> {
        val dimensions = mutableListOf<Int>()
        var level = levelBelow(source)

        while (level != null) {
            dimensions.add(level.size)
            level = levelBelow(level.firstOrNull())
        }

        return dimensions.map { ArrayDimension(it, 1) }
    }

    private fun findFirstNonNull(source: Any): Any? {
        val level = levelBelow(source) ?: return source
        for (item in level) {
            val found = findFirstNonNull(item ?: continue)
            if (found != null) return found
        }
        return null
    }

    /** The class of the elements an `Array<T>` holds, however deeply nested, or `null` where it says nothing. */
    private fun elementClassOf(source: Any): KClass<*>? {
        var jClass: Class<*> = source.javaClass
        if (!jClass.isArray) return null
        while (jClass.isArray && jClass != ByteArray::class.java) jClass = jClass.componentType
        return if (jClass == Any::class.java) null else jClass.kotlin
    }

    private fun elementOidOf(element: Any, context: SerializationContext): Int? {
        return when (val converted = context.convert(element, UNRESOLVED_OID)) {
            is PgTyped -> context.types.resolveOid(converted.pgType.name, converted.pgType.schema, converted.pgType.isArray)
            is PgContainer -> converted.containerOid
            null -> null
            else -> context.types.codecs.getCodecByClass(converted::class)
                ?.let { context.types.codecs.getOidForCodec(it) ?: context.types.resolveOid(it.pgTypeName, it.pgSchema) }
        }
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {
        val dimensions = getDimensions(source)
        val expectedSize = dimensions.fold(1) { acc, dim -> acc * dim.size }

        val arrayType = if (expectedOid.isKnownOid) {
            context.types.dictionary.getPgType(expectedOid) as? PgType.Array
        } else {
            val elementOid = findFirstNonNull(source)?.let { elementOidOf(it, context) }
                ?: elementClassOf(source)?.let { context.defaultOidForClass(it) }

            elementOid?.let { context.types.dictionary.getArrayType(it) }
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
        val position = IntArray(dimensions.size)

        fun describe(item: Any?, level: Collection<Any?>?) = when {
            level != null -> "${level.size} entries"
            item == null -> "null"
            else -> "a value"
        }

        fun notRectangular(depth: Int, item: Any?, level: Collection<Any?>?): MappingException {
            val expected = if (depth < dimensions.size) "${dimensions[depth].size} entries" else "a value"
            val e = MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "Multidimensional arrays must be rectangular, and this is ${describe(item, level)} " +
                        "where the first at this depth is $expected"
            )
            for (d in depth - 1 downTo 0) e.path.add("[${position[d]}]")
            return e
        }

        fun flattenAndConvert(item: Any?, depth: Int) {
            val level = levelBelow(item)
            if (depth < dimensions.size) {
                if (level == null || level.size != dimensions[depth].size) throw notRectangular(depth, item, level)
                var i = 0
                for (child in level) {
                    position[depth] = i++
                    flattenAndConvert(child, depth + 1)
                }
                return
            }
            if (level != null) throw notRectangular(depth, item, level)
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

        flattenAndConvert(source, 0)

        return PgArray(
            arrayOid = arrayType.oid,
            elementOid = elementOid,
            dimensions = dimensions,
            elements = convertedElements
        )
    }
}
