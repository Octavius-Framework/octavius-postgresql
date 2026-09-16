package io.github.octaviusframework.driver.codec.dynamic

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.codec.decodeSafely
import io.github.octaviusframework.driver.codec.encodeSafely
import io.github.octaviusframework.driver.container.*
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.io.getIntBE
import io.github.octaviusframework.driver.registry.CodecScope
import io.github.octaviusframework.driver.type.PgType


/**
 * Utility object for parsing and serializing PostgreSQL container types.
 * Supports arrays, composites, records, ranges, and multiranges.
 */
internal object ContainerCodec {

    // ------------------------------------------PARSERS----------------------------------------------------------------

    /**
     * Parses a generic field, which can be either a container or a primitive type.
     */
    private fun parseField(data: ByteArray, offset: Int, length: Int, oid: Int, scope: CodecScope): Any {
        val codec = scope.codecs.getCodecByOid<Any>(oid)
            ?: throw TypeException(
                TypeExceptionReason.MISSING_CODEC,
                oid = oid,
                details = "Parsing field of oid $oid"
            )
        return codec.decodeSafely(data, offset, length)
    }

    /**
     * Parses a byte array into a [PgContainer] based on the OID.
     */
    fun parseContainer(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgContainer {
        return when (val pgType = scope.types.getPgType(oid)) {
            is PgType.Array -> parsePgArray(data, offset, pgType.oid, scope)
            is PgType.Composite -> parsePgComposite(data, offset, pgType.oid, scope)
            is PgType.Range -> parsePgRange(data, offset, pgType.oid, scope)
            is PgType.Multirange -> parsePgMultirange(data, offset, pgType.oid, scope)
            is PgType.Record -> parsePgRecord(data, offset, pgType.oid, scope)
            else -> error("Unknown pg type in container parsing")
        }
    }

    /**
     * Parses a PostgreSQL array from its binary format.
     *
     * @param data The byte array containing the payload.
     * @param offset The starting position in the byte array.
     * @param oid The OID of the array type.
     * @param scope The dictionaries this codec resolves nested values through.
     * @return The parsed [PgArray].
     */
    fun parsePgArray(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgArray {
        var localOffset = offset

        val ndims = data.getIntBE(localOffset); localOffset += 4
        localOffset += 4 // hasNullsInt ignored
        val elementOid = data.getIntBE(localOffset); localOffset += 4

        val dimensions = mutableListOf<ArrayDimension>()
        for (i in 0 until ndims) {
            val size = data.getIntBE(localOffset); localOffset += 4
            val lowerBound = data.getIntBE(localOffset); localOffset += 4
            dimensions.add(ArrayDimension(size, lowerBound))
        }

        val totalElements = dimensions.fold(1) { acc, dim -> acc * dim.size }
        val count = if (ndims == 0) 0 else totalElements

        val elements = ArrayList<Any?>(count)

        for (i in 0 until count) {
            val len = data.getIntBE(localOffset); localOffset += 4
            if (len == -1) {
                elements.add(null)
            } else {
                elements.add(parseField(data, localOffset, len, elementOid, scope))
                localOffset += len
            }
        }

        return PgArray(oid, elementOid, dimensions, elements)
    }

    /**
     * Parses a PostgreSQL composite type (row) from its binary format.
     *
     * @param data The byte array containing the payload.
     * @param offset The starting position in the byte array.
     * @param oid The OID of the composite type.
     * @param scope The dictionaries this codec resolves nested values through.
     * @return The parsed [PgComposite].
     */
    fun parsePgComposite(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgComposite {
        val pgType = scope.types.getPgType(oid) as? PgType.Composite
            ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = oid,
                details = "Expected Composite type"
            )

        var localOffset = offset
        val numFields = data.getIntBE(localOffset); localOffset += 4

        val fields = arrayOfNulls<Any?>(numFields)
        for (i in 0 until numFields) {
            val fieldOid = data.getIntBE(localOffset); localOffset += 4
            val len = data.getIntBE(localOffset); localOffset += 4
            if (len != -1) {
                fields[i] = parseField(data, localOffset, len, fieldOid, scope)
                localOffset += len
            }
        }

        return PgComposite(pgType, fields)
    }

    /**
     * Parses an anonymous PostgreSQL record type from its binary format.
     *
     * @param data The byte array containing the payload.
     * @param offset The starting position in the byte array.
     * @param oid The OID of the record type.
     * @param scope The dictionaries this codec resolves nested values through.
     * @return The parsed [PgRecord].
     */
    fun parsePgRecord(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgRecord {
        val pgType = scope.types.getPgType(oid) as? PgType.Record
            ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = oid,
                details = "Expected Record type"
            )

        var localOffset = offset
        val numFields = data.getIntBE(localOffset); localOffset += 4

        val fields = Array<Any?>(numFields) { null }
        val fieldOids = IntArray(numFields) { 0 }

        for (i in 0 until numFields) {
            val fieldOid = data.getIntBE(localOffset); localOffset += 4
            val len = data.getIntBE(localOffset); localOffset += 4
            fieldOids[i] = fieldOid
            if (len == -1) {
                fields[i] = null
            } else {
                fields[i] = parseField(data, localOffset, len, fieldOid, scope)
                localOffset += len
            }
        }

        return PgRecord(pgType, fieldOids, fields)
    }

    /**
     * Parses a PostgreSQL range type from its binary format.
     *
     * @param data The byte array containing the payload.
     * @param offset The starting position in the byte array.
     * @param oid The OID of the range type.
     * @param scope The dictionaries this codec resolves nested values through.
     * @return The parsed [PgRange].
     */
    fun parsePgRange(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgRange {
        val pgType = scope.types.getPgType(oid) as? PgType.Range
            ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = oid,
                details = "Expected Range type"
            )

        var localOffset = offset
        val flags = data[localOffset]; localOffset += 1

        val isEmpty = (flags.toInt() and 0x01) != 0
        val isLowerInfinite = (flags.toInt() and 0x08) != 0
        val isLowerNull = (flags.toInt() and 0x20) != 0
        val isUpperInfinite = (flags.toInt() and 0x10) != 0
        val isUpperNull = (flags.toInt() and 0x40) != 0

        var lowerBound: Any? = null
        if (!isEmpty && !isLowerInfinite && !isLowerNull) {
            val len = data.getIntBE(localOffset); localOffset += 4
            lowerBound = parseField(data, localOffset, len, pgType.subtypeOid, scope)
            localOffset += len
        }

        var upperBound: Any? = null
        if (!isEmpty && !isUpperInfinite && !isUpperNull) {
            val len = data.getIntBE(localOffset); localOffset += 4
            upperBound = parseField(data, localOffset, len, pgType.subtypeOid, scope)
            localOffset += len
        }

        return PgRange(oid, pgType.subtypeOid, flags, lowerBound, upperBound)
    }

    /**
     * Parses a PostgreSQL multirange type from its binary format.
     *
     * @param data The byte array containing the payload.
     * @param offset The starting position in the byte array.
     * @param oid The OID of the multirange type.
     * @param scope The dictionaries this codec resolves nested values through.
     * @return The parsed [PgMultirange].
     */
    fun parsePgMultirange(data: ByteArray, offset: Int, oid: Int, scope: CodecScope): PgMultirange {
        val pgType = scope.types.getPgType(oid) as? PgType.Multirange
            ?: throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                oid = oid,
                details = "Expected Multirange type"
            )

        var localOffset = offset
        val numRanges = data.getIntBE(localOffset); localOffset += 4

        val ranges = mutableListOf<PgRange>()
        for (i in 0 until numRanges) {
            val len = data.getIntBE(localOffset); localOffset += 4
            ranges.add(parsePgRange(data, localOffset, pgType.rangeOid, scope))
            localOffset += len
        }

        return PgMultirange(pgType.oid, pgType.rangeOid, ranges)
    }

    // ----------------------------------------------SERIALIZERS--------------------------------------------------------

    /**
     * Serializes a [PgContainer] into the provided [PgByteWriter].
     */
    fun serializeContainer(container: PgContainer, writer: PgByteWriter, scope: CodecScope) {
        when (container) {
            is PgArray -> serializePgArray(container, writer, scope)
            is PgComposite -> serializePgComposite(container, writer, scope)
            is PgRange -> serializePgRange(container, writer, scope)
            is PgMultirange -> serializePgMultirange(container, writer, scope)
            is PgRecord -> serializePgRecord()
            else -> throw TypeException(
                TypeExceptionReason.NOT_A_CONTAINER,
                typeName = container::class.simpleName,
                details = "Unknown container type"
            )
        }
    }

    /**
     * Writes a single field (primitive or container) to the provided [PgByteWriter].
     * Uses length prefix framing as expected by the PostgreSQL binary protocol.
     */
    private fun writeField(
        value: Any?,
        expectedOid: Int,
        writer: PgByteWriter,
        scope: CodecScope
    ) {
        if (value == null) {
            writer.writeInt(-1)
            return
        }

        val codec = scope.codecs.getCodecByOid<Any>(expectedOid)
            ?: throw TypeException(
                TypeExceptionReason.MISSING_CODEC,
                oid = expectedOid,
                details = "Serializing value: $value"
            )
        val marker = writer.reserveLengthInt()
        codec.encodeSafely(value, writer)
        writer.fillLengthInt(marker)
    }

    /**
     * Serializes a [PgArray] into the provided [PgByteWriter].
     *
     * @param array The array container to serialize.
     * @param writer The binary packet writer.
     * @param scope The dictionaries this codec resolves nested values through.
     */
    fun serializePgArray(array: PgArray, writer: PgByteWriter, scope: CodecScope) {
        val count = array.totalElements
        val hasNulls = array.elements.any { it == null }

        writer.writeInt(array.dimensions.size)
        writer.writeInt(if (hasNulls) 1 else 0)
        writer.writeInt(array.elementOid)

        for (dim in array.dimensions) {
            writer.writeInt(dim.size)
            writer.writeInt(dim.lowerBound)
        }

        for (i in 0 until count) {
            writeField(array.elements[i], array.elementOid, writer, scope)
        }
    }

    /**
     * Serializes a [PgComposite] into the provided [PgByteWriter].
     *
     * @param composite The composite container to serialize.
     * @param writer The binary packet writer.
     * @param scope The dictionaries this codec resolves nested values through.
     */
    fun serializePgComposite(composite: PgComposite, writer: PgByteWriter, scope: CodecScope) {
        writer.writeInt(composite.fields.size)
        val attributeOids = composite.type.attributeOids
        for (i in composite.fields.indices) {
            writer.writeInt(attributeOids[i])
            writeField(composite.fields[i], attributeOids[i], writer, scope)
        }
    }

    /**
     * Attempting to serialize an anonymous record will throw an exception,
     * since PostgreSQL does not accept anonymous records as bound parameters.
     */
    fun serializePgRecord() {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "PostgreSQL cannot accept an anonymous record (OID 2249) as a bound parameter. A PgRecord only " +
                "comes out of a result and cannot go back in as one - pass a registered composite type instead."
        )
    }

    /**
     * Serializes a [PgRange] into the provided [PgByteWriter].
     *
     * @param range The range container to serialize.
     * @param writer The binary packet writer.
     * @param scope The dictionaries this codec resolves nested values through.
     */
    fun serializePgRange(range: PgRange, writer: PgByteWriter, scope: CodecScope) {
        writer.writeByte(range.flags)

        if (!range.isEmpty) {
            if (!range.isLowerInfinite && !range.isLowerNull) {
                writeField(range.lowerBound, range.elementOid, writer, scope)
            }
            if (!range.isUpperInfinite && !range.isUpperNull) {
                writeField(range.upperBound, range.elementOid, writer, scope)
            }
        }
    }

    /**
     * Serializes a [PgMultirange] into the provided [PgByteWriter].
     *
     * @param multirange The multirange container to serialize.
     * @param writer The binary packet writer.
     * @param scope The dictionaries this codec resolves nested values through.
     */
    fun serializePgMultirange(multirange: PgMultirange, writer: PgByteWriter, scope: CodecScope) {
        writer.writeInt(multirange.ranges.size)
        for (range in multirange.ranges) {
            val marker = writer.reserveLengthInt()
            serializePgRange(range, writer, scope)
            writer.fillLengthInt(marker)
        }
    }
}


