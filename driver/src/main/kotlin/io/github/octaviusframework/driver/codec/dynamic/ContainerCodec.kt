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


/** Reads and writes the binary form of arrays, composites, records, ranges and multiranges. */
internal object ContainerCodec {

    // ------------------------------------------PARSERS----------------------------------------------------------------

    /** Reads one nested value, a container or a scalar. */
    private fun parseField(data: ByteArray, offset: Int, length: Int, oid: Int, scope: CodecScope): Any {
        val codec = scope.codecs.getCodecByOid<Any>(oid)
            ?: throw TypeException(
                TypeExceptionReason.MISSING_CODEC,
                oid = oid,
                details = "Parsing field of oid $oid"
            )
        return codec.decodeSafely(data, offset, length)
    }

    /** Reads the container of type [oid] at [offset]. */
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

    /** Reads an array of type [oid], its elements through [scope]. */
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

    /** Reads a composite of type [oid], its attributes through [scope]. */
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

    /** Reads an anonymous record, its fields through [scope]. */
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

    /** Reads a range of type [oid], its bounds through [scope]. */
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

    /** Reads a multirange of type [oid], its ranges through [scope]. */
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

    /** Writes [container]. */
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

    /** Writes one nested value, a container or a scalar, behind its length. */
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

    /** Writes [array], its elements through [scope]. */
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

    /** Writes [composite], its attributes through [scope]. */
    fun serializePgComposite(composite: PgComposite, writer: PgByteWriter, scope: CodecScope) {
        writer.writeInt(composite.fields.size)
        val attributeOids = composite.type.attributeOids
        for (i in composite.fields.indices) {
            writer.writeInt(attributeOids[i])
            writeField(composite.fields[i], attributeOids[i], writer, scope)
        }
    }

    /** Refuses: PostgreSQL does not take an anonymous record as a parameter. */
    fun serializePgRecord() {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "PostgreSQL cannot accept an anonymous record (OID 2249) as a bound parameter. A PgRecord only " +
                "comes out of a result and cannot go back in as one - pass a registered composite type instead."
        )
    }

    /** Writes [range], its bounds through [scope]. */
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

    /** Writes [multirange], its ranges through [scope]. */
    fun serializePgMultirange(multirange: PgMultirange, writer: PgByteWriter, scope: CodecScope) {
        writer.writeInt(multirange.ranges.size)
        for (range in multirange.ranges) {
            val marker = writer.reserveLengthInt()
            serializePgRange(range, writer, scope)
            writer.fillLengthInt(marker)
        }
    }
}


