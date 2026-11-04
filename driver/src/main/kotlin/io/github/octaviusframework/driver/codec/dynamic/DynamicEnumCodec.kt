package io.github.octaviusframework.driver.codec.dynamic

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.TypeCodec

/** Reads and writes an enum as its label. */
internal class DynamicEnumCodec(
    override val oid: Int,
    override val pgTypeName: String,
    override val pgSchema: String
) : TypeCodec<String> {
    override val kotlinClass = String::class
    override val isDefaultForKotlinType = false

    override val fromBinary: (ByteArray, Int, Int) -> String = { data, offset, length ->
        String(data, offset, length, Charsets.UTF_8)
    }

    override val toBinary: (String, PgByteWriter) -> Unit = { value, writer -> writer.writeBytes(value.toByteArray(Charsets.UTF_8)) }
}
