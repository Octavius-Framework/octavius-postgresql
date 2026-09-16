package io.github.octaviusframework.driver.codec.dynamic

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.registry.CodecScope
import kotlin.reflect.KClass

/**
 * Dynamic codec for PostgreSQL container types (Array, Composite, Range, Multirange, Record).
 * It delegates the actual parsing and serialization to the [ContainerCodec] object.
 */
internal class DynamicContainerCodec<T : PgContainer>(
    override val oid: Int,
    override val pgTypeName: String,
    override val pgSchema: String,
    override val kotlinClass: KClass<T>,
    private val scope: CodecScope
) : TypeCodec<T> {

    override val isDefaultForKotlinType = false

    @Suppress("UNCHECKED_CAST")
    override val fromBinary: (ByteArray, Int, Int) -> T = { data, offset, _ ->
        ContainerCodec.parseContainer(data, offset, oid, scope) as T
    }

    override val toBinary: (T, PgByteWriter) -> Unit = { value, writer ->
        ContainerCodec.serializeContainer(value, writer, scope)
    }
}
