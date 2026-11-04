package io.github.octaviusframework.driver.codec.dynamic

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.registry.CodecScope
import kotlin.reflect.KClass

/** Reads and writes a domain through the codec of its base type [baseTypeOid], found through [scope]. */
internal class DynamicDomainCodec<T : Any>(
    override val oid: Int,
    override val pgTypeName: String,
    override val pgSchema: String,
    private val baseTypeOid: Int,
    private val scope: CodecScope
) : TypeCodec<T> {

    @Suppress("UNCHECKED_CAST")
    private val delegate: TypeCodec<T>
        get() = scope.codecs.getCodecByOid(baseTypeOid)
            ?: throw TypeException(TypeExceptionReason.MISSING_CODEC, oid = baseTypeOid, details = "Serializer not found for base domain type with OID $baseTypeOid")

    override val kotlinClass: KClass<T>
        get() = delegate.kotlinClass

    override val isDefaultForKotlinType = false

    override val fromBinary: (ByteArray, Int, Int) -> T
        get() = delegate.fromBinary

    override val toBinary: (T, PgByteWriter) -> Unit
        get() = delegate.toBinary
}

