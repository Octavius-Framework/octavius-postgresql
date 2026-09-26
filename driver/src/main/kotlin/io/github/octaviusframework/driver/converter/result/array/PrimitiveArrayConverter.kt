package io.github.octaviusframework.driver.converter.result.array

import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal object PrimitiveArrayConverter : ResultConverter<PgArray, Any> {

    override val supportedSourceClass = PgArray::class
    
    private val intKType = typeOf<Int>()
    private val doubleKType = typeOf<Double>()
    private val floatKType = typeOf<Float>()
    private val longKType = typeOf<Long>()
    private val shortKType = typeOf<Short>()
    private val byteKType = typeOf<Byte>()
    private val booleanKType = typeOf<Boolean>()
    private val charKType = typeOf<Char>()

    override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
        val classifier = expectedType.classifier
        return classifier == IntArray::class ||
               classifier == DoubleArray::class ||
               classifier == FloatArray::class ||
               classifier == LongArray::class ||
               classifier == ShortArray::class ||
               classifier == ByteArray::class ||
               classifier == BooleanArray::class ||
               classifier == CharArray::class
    }

    override fun convert(
        source: PgArray,
        expectedType: KType,
        sourceType: PgType,
        context: DeserializationContext
    ): Any {
        if (source.dimensions.size > 1) {
            throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "$expectedType holds one dimension, the array has ${source.dimensions.size}"
            )
        }

        val pgElementType = context.types.dictionary.getPgType(source.elementOid)
        val elements = source.elements
        val size = elements.size

        return when (expectedType.classifier) {
            IntArray::class -> {
                val result = IntArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, intKType, pgElementType)
                }
                result
            }

            DoubleArray::class -> {
                val result = DoubleArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, doubleKType, pgElementType)
                }
                result
            }
            FloatArray::class -> {
                val result = FloatArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, floatKType, pgElementType)
                }
                result
            }

            LongArray::class -> {
                val result = LongArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, longKType, pgElementType)
                }
                result
            }

            ShortArray::class -> {
                val result = ShortArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, shortKType, pgElementType)
                }
                result
            }

            ByteArray::class -> {
                val result = ByteArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, byteKType, pgElementType)
                }
                result
            }

            BooleanArray::class -> {
                val result = BooleanArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, booleanKType, pgElementType)
                }
                result
            }

            CharArray::class -> {
                val result = CharArray(size)
                for (i in 0 until size) {
                    val value = elements[i]!!
                    result[i] = context.convert(value, charKType, pgElementType)
                }
                result
            }
            else -> error("Unsupported primitive array type")
        }
    }
}
