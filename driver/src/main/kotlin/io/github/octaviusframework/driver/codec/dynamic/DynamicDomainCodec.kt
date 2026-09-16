package io.github.octaviusframework.driver.codec.dynamic

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.registry.CodecScope
import kotlin.reflect.KClass

/**
 * Codec for PostgreSQL domain types, which are essentially custom types based on underlying base types.
 * It delegates serialization and deserialization to the codec of the base type.
 *
 * @param T The Kotlin type of the underlying base type.
 * @property oid The OID of the domain type.
 * @property pgTypeName The name of the domain type in PostgreSQL.
 * @property pgSchema The schema where the domain type is defined.
 * @property baseTypeOid The OID of the underlying base type.
 * @property scope The dictionaries this codec resolves its base type through.
 */
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

