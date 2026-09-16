package io.github.octaviusframework.driver.converter.parameter.array

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.isKnownOid
import kotlin.reflect.KClass

internal object PrimitiveArrayParameterConverter : ParameterConverter<Any> {

    override val supportedClass: KClass<Any> = Any::class

    override fun canConvert(sourceClass: KClass<*>, expectedOid: Int, context: SerializationContext): Boolean {
        if (sourceClass == ByteArray::class) return false
        return sourceClass.java.isArray && sourceClass.java.componentType?.isPrimitive == true
    }

    override fun convert(source: Any, expectedOid: Int, context: SerializationContext): Any {

        val arrayType = if (expectedOid.isKnownOid) {
            context.types.dictionary.getPgType(expectedOid) as? PgType.Array
        } else {
            val componentType = source.javaClass.componentType?.kotlin
            if (componentType != null) {
                val elementOid = context.types.codecs.getCodecByClass(componentType)?.oid
                if (elementOid != null) {
                    context.types.dictionary.getArrayType(elementOid)
                } else null
            } else null
        }

        if (arrayType == null) {
            throw TypeException(
                TypeExceptionReason.TYPE_NOT_FOUND,
                details = "Cannot infer array type for the primitive array. The array is empty, or the element type is unknown. Use explicit typing (e.g. .withPgType(...))."
            )
        }

        val elementOid = arrayType.elementOid

        val size = java.lang.reflect.Array.getLength(source)
        val convertedElements = ArrayList<Any?>(size)

        for (i in 0 until size) {
            val item = java.lang.reflect.Array.get(source, i)
            try {
                convertedElements.add(context.convert(item, elementOid, null))
            } catch (e: MappingException) {
                e.path.add("[$i]")
                throw e
            }
        }

        val dimensions = listOf(ArrayDimension(size, 1))

        return PgArray(
            arrayOid = arrayType.oid,
            elementOid = elementOid,
            dimensions = dimensions,
            elements = convertedElements
        )
    }
}

