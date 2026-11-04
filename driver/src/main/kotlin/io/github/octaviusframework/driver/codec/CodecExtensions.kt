package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.exception.CodecAction
import io.github.octaviusframework.driver.exception.CodecException
import io.github.octaviusframework.driver.io.PgByteWriter
import kotlin.math.min

/** Runs [TypeCodec.fromBinary], raising what it throws as a [CodecException]. */
internal fun <T : Any> TypeCodec<T>.decodeSafely(data: ByteArray, offset: Int, length: Int): T {
    return try {
        this.fromBinary(data, offset, length)
    } catch (e: Exception) {
        val truncatedLength = min(length, 100)
        val valueToLog = if (offset + truncatedLength <= data.size) {
            data.copyOfRange(offset, offset + truncatedLength)
        } else {
            data
        }
        throw CodecException(
            action = CodecAction.DECODING,
            value = valueToLog,
            name = this.pgTypeName,
            schema = this.pgSchema,
            oid = this.oid,
            kotlinClass = this.kotlinClass,
            cause = e
        )
    }
}

/** Runs [TypeCodec.toBinary], raising what it throws as a [CodecException]. */
internal fun <T : Any> TypeCodec<T>.encodeSafely(value: T, writer: PgByteWriter) {
    try {
        this.toBinary(value, writer)
    } catch (e: Exception) {
        throw CodecException(
            action = CodecAction.ENCODING,
            value = value,
            name = this.pgTypeName,
            schema = this.pgSchema,
            oid = this.oid,
            kotlinClass = this.kotlinClass,
            cause = e
        )
    }
}
