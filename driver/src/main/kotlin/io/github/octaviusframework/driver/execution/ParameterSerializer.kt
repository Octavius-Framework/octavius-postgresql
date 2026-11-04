package io.github.octaviusframework.driver.execution

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.TypeCodec
import io.github.octaviusframework.driver.codec.encodeSafely
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterMapper
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.type.PgTyped
import io.github.octaviusframework.driver.registry.TypeLookup
import io.github.octaviusframework.driver.type.UNRESOLVED_OID
import io.github.octaviusframework.driver.type.isKnownOid

/** The longest a parameter may serialize to: the server's `MaxAllocSize` less a value's four-byte header. */
private const val MAX_PARAMETER_LENGTH = 1073741819

/**
 * Turns bound values, once converted, into the bytes of a `Bind` and reports the OID each went out as: a [PgTyped]
 * or a [PgContainer] carries its own, anything else is its codec's. One per execution, over its pinned catalog.
 */
internal class ParameterSerializer(
    private val types: TypeLookup,
    private val parameterMapper: ParameterMapper
) {

    internal fun serializeAll(parameters: Array<out Any?>, writer: PgByteWriter): IntArray {
        writer.clear()
        val size = parameters.size
        val oids = IntArray(size)

        for (i in 0 until size) {
            val marker = writer.reserveLengthInt()
            oids[i] = try {
                serializeValue(parameters[i], writer, marker)
            } catch (e: OctaviusException) {
                e.path.add("\$${i + 1}")
                throw e
            }
            val length = writer.position - marker - 4
            if (length > MAX_PARAMETER_LENGTH) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    "Parameter \$${i + 1} serialized to $length bytes; PostgreSQL refuses a value above $MAX_PARAMETER_LENGTH"
                )
            }
        }

        return oids
    }

    private fun serializeValue(parameter: Any?, writer: PgByteWriter, marker: Int): Int {
        var oid = UNRESOLVED_OID
        var value = parameter

        if (value is PgTyped) {
            oid = types.resolveOid(value.pgType.name, value.pgType.schema, value.pgType.isArray)
            value = value.value
        }

        if (value != null && value is PgContainer) {
            return writeKnown(value, value.containerOid, writer, marker)
        }

        value = parameterMapper.convert(value, oid)

        if (value is PgTyped) {
            oid = types.resolveOid(value.pgType.name, value.pgType.schema, value.pgType.isArray)
            value = value.value
        }

        if (value == null) {
            writer.updatePosition(marker)
            writer.writeInt(-1)
            return oid
        }

        if (value is PgContainer) {
            return writeKnown(value, value.containerOid, writer, marker)
        }

        return if (oid.isKnownOid) {
            writeKnown(value, oid, writer, marker)
        } else {
            writeStandard(value, writer, marker)
        }
    }

    private fun writeKnown(value: Any, oid: Int, writer: PgByteWriter, marker: Int): Int {
        val codec = types.codecs.getCodecByOid<Any>(oid)
            ?: throw TypeException(TypeExceptionReason.MISSING_CODEC, oid = oid, details = "Codec not found")

        codec.encodeSafely(value, writer)
        writer.fillLengthInt(marker)
        return oid
    }

    private fun writeStandard(value: Any, writer: PgByteWriter, marker: Int): Int {
        val codec = types.codecs.getCodecByClass(value::class)
            ?: throw TypeException(TypeExceptionReason.MISSING_CODEC, details = "Codec not found for: ${value::class.qualifiedName}")

        @Suppress("UNCHECKED_CAST")
        (codec as TypeCodec<Any>).encodeSafely(value, writer)
        writer.fillLengthInt(marker)

        return types.codecs.getOidForCodec(codec) ?: types.resolveOid(codec.pgTypeName, codec.pgSchema)
    }
}
