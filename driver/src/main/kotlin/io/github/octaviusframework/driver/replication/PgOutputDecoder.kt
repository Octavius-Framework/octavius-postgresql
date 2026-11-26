package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.io.getIntBE
import io.github.octaviusframework.driver.io.getLongBE
import io.github.octaviusframework.driver.io.getShortBE
import io.github.octaviusframework.driver.message.backend.FieldDescription
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.row.RowMetadata
import kotlin.time.Instant

/** The binary format code, the only one the driver reads values in. */
private const val BINARY_FORMAT: Short = 1

/** The length a [Tuple] gives a column that came without its value, beside `-1` for a `NULL`. */
private const val UNCHANGED = -2

/** What a pgoutput message stands for in a stream: a transaction's start, a change of it, or its commit. */
internal sealed interface PgOutputFrame {
    /** The start of transaction [xid], which committed at [commitLsn] at [committedAt]. */
    class Begin(val xid: Long, val commitLsn: PgLsn, val committedAt: Instant) : PgOutputFrame

    /** A change of the transaction begun. */
    class Of(val change: Change) : PgOutputFrame

    /** The commit of the transaction begun, ending at [endLsn]. */
    class Commit(val endLsn: PgLsn) : PgOutputFrame
}

/** Where each value of a row lies in its message, by column; a length of `-1` is a `NULL`, [UNCHANGED] none. */
private class Tuple(val offsets: IntArray, val lengths: IntArray)

/**
 * Decodes the messages of pgoutput, protocol version 1. Keeps the relations it has been told about, whose
 * changes follow, and reads their rows against [types].
 */
internal class PgOutputDecoder(val types: StreamTypes) {

    private val relations = HashMap<Int, PgRelation>()

    /**
     * The frame [data] carries, or `null` for a message the decoder keeps to itself: a relation, the names of its
     * types, a transaction's origin, the abort of a transaction never sent.
     *
     * @throws TypeException if a column's type is not in the catalog, or its value came as text.
     */
    fun decode(data: ReplicationData): PgOutputFrame? {
        val reader = Reader(data.payload)
        return when (val tag = reader.char()) {
            'B' -> {
                val commitLsn = PgLsn(reader.long())
                val committedAt = pgTimestamp(reader.long())
                PgOutputFrame.Begin(reader.int().toLong() and 0xFFFF_FFFFL, commitLsn, committedAt)
            }
            'C' -> {
                reader.byte() // flags, none defined
                reader.long() // the commit's position, which its Begin gave
                PgOutputFrame.Commit(PgLsn(reader.long()))
            }
            'R' -> {
                describe(reader)
                null
            }
            // The relation that follows names its types by OID, and a transaction's origin is not asked about.
            'Y', 'O' -> null
            'I' -> {
                val relation = relationOf(reader.int())
                reader.expect('N')
                val new = row(reader.data, relation.metadata, tuple(reader, relation), included = null)
                PgOutputFrame.Of(Change.Insert(relation, new))
            }
            'U' -> PgOutputFrame.Of(update(reader, relationOf(reader.int())))
            'D' -> {
                val relation = relationOf(reader.int())
                val kind = reader.char()
                if (kind != 'K' && kind != 'O') throw unexpected("a delete of $relation without its old row")
                PgOutputFrame.Of(Change.Delete(relation, old(reader.data, relation, tuple(reader, relation), kind)))
            }
            'T' -> {
                val count = reader.int()
                val options = reader.byte().toInt()
                val truncated = List(count) { relationOf(reader.int()) }
                PgOutputFrame.Of(
                    Change.Truncate(truncated, cascade = options and 1 != 0, restartIdentity = options and 2 != 0)
                )
            }
            // A stream abort, which PostgreSQL 18 before 18.7 sends whatever the protocol version for an aborted
            // transaction it dropped unsent (BUG #19616). It ends what was never handed out.
            'A' -> null
            else -> throw unexpected("the message '$tag'")
        }
    }

    /**
     * Reads an update of [relation]. A whole old row, under `REPLICA IDENTITY FULL`, gives the values the new one
     * came without; the new row carries only the columns that came, and the update names the others.
     */
    private fun update(reader: Reader, relation: PgRelation): Change.Update {
        val oldKind = reader.char()
        val oldTuple = if (oldKind == 'K' || oldKind == 'O') tuple(reader, relation) else null
        if ((if (oldTuple == null) oldKind else reader.char()) != 'N') {
            throw unexpected("an update of $relation without its new row")
        }
        val newTuple = tuple(reader, relation)

        if (oldKind == 'O' && oldTuple != null) {
            for (i in newTuple.lengths.indices) {
                if (newTuple.lengths[i] == UNCHANGED && oldTuple.lengths[i] != UNCHANGED) {
                    newTuple.offsets[i] = oldTuple.offsets[i]
                    newTuple.lengths[i] = oldTuple.lengths[i]
                }
            }
        }
        val old = oldTuple?.let { old(reader.data, relation, it, oldKind) }
        val sent = BooleanArray(newTuple.lengths.size) { newTuple.lengths[it] != UNCHANGED }
        if (sent.all { it }) {
            return Change.Update(relation, old, row(reader.data, relation.metadata, newTuple, included = null), emptySet())
        }
        val unchanged = relation.metadata.columnNames.filterIndexedTo(LinkedHashSet()) { i, _ -> !sent[i] }
        val metadata = RowMetadata(relation.fields.filterIndexed { i, _ -> sent[i] }, types.mapper().catalog.dictionary)
        return Change.Update(relation, old, row(reader.data, metadata, newTuple, sent), unchanged)
    }

    /** The old row of [relation]: whole for [kind] `'O'`, of the key columns alone for `'K'`. */
    private fun old(data: ByteArray, relation: PgRelation, tuple: Tuple, kind: Char): Row =
        if (kind == 'K') {
            row(data, relation.keyMetadata, tuple, relation.isKey)
        } else {
            row(data, relation.metadata, tuple, included = null)
        }

    /**
     * Takes in a relation message.
     *
     * @throws TypeException `TYPE_NOT_FOUND` if it names a type the catalog does not describe.
     */
    private fun describe(reader: Reader) {
        val oid = reader.int()
        val schema = reader.cstring().ifEmpty { "pg_catalog" }
        val name = reader.cstring()
        val identity = when (val code = reader.char()) {
            'd' -> ReplicaIdentity.DEFAULT
            'n' -> ReplicaIdentity.NOTHING
            'f' -> ReplicaIdentity.FULL
            'i' -> ReplicaIdentity.INDEX
            else -> throw unexpected("the replica identity '$code' for $schema.$name")
        }
        val count = reader.short().toInt()
        val isKey = BooleanArray(count)
        val names = Array(count) { "" }
        val typeOids = IntArray(count)
        val modifiers = IntArray(count)
        for (i in 0 until count) {
            isKey[i] = reader.byte().toInt() and 1 != 0
            names[i] = reader.cstring()
            typeOids[i] = reader.int()
            modifiers[i] = reader.int()
        }

        types.requireDescribed(typeOids)
        val dictionary = types.mapper().catalog.dictionary
        // Described as a query describes a column: a domain as the type under it.
        val fields = List(count) { i ->
            FieldDescription(names[i], 0, 0, dictionary.getBaseType(typeOids[i]).oid, 0, modifiers[i], BINARY_FORMAT)
        }
        val keyMetadata = RowMetadata(fields.filterIndexed { i, _ -> isKey[i] }, dictionary)
        relations[oid] = PgRelation(
            oid, schema, name, identity, keyMetadata.columnNames, RowMetadata(fields, dictionary), fields, isKey,
            keyMetadata
        )
    }

    private fun relationOf(oid: Int): PgRelation =
        relations[oid] ?: throw unexpected("a change of the relation $oid before describing it")

    /** Reads where each value of a row of [relation] lies. */
    private fun tuple(reader: Reader, relation: PgRelation): Tuple {
        val count = reader.short().toInt()
        if (count != relation.metadata.size) {
            throw unexpected("a row of $relation with $count columns, where it has ${relation.metadata.size}")
        }
        val offsets = IntArray(count)
        val lengths = IntArray(count)
        for (i in 0 until count) {
            lengths[i] = when (val kind = reader.char()) {
                'n' -> -1
                'u' -> UNCHANGED
                'b' -> reader.int().also {
                    offsets[i] = reader.position
                    reader.skip(it)
                }
                't' -> {
                    val type = relation.metadata.columns[i]
                    throw TypeException(
                        TypeExceptionReason.MISSING_CODEC,
                        oid = type.oid,
                        details = "Column '${type.name}' of $relation came as text, since its type has no binary " +
                            "send function, and the driver reads values only in binary."
                    )
                }
                else -> throw unexpected("the column kind '$kind' in a row of $relation")
            }
        }
        return Tuple(offsets, lengths)
    }

    /**
     * The row [metadata] describes, of the columns of [tuple] that [included] marks, or of every one without it,
     * as they lie in [data].
     */
    private fun row(data: ByteArray, metadata: RowMetadata, tuple: Tuple, included: BooleanArray?): Row {
        val mapper = types.mapper()
        if (included == null) return Row(data, tuple.offsets, tuple.lengths, metadata, mapper.catalog, mapper)

        val offsets = IntArray(metadata.size)
        val lengths = IntArray(metadata.size)
        var column = 0
        for (i in tuple.lengths.indices) {
            if (!included[i]) continue
            offsets[column] = tuple.offsets[i]
            lengths[column] = tuple.lengths[i]
            column++
        }
        return Row(data, offsets, lengths, metadata, mapper.catalog, mapper)
    }

    private fun unexpected(what: String) = InvalidOperationException(
        InvalidOperationExceptionReason.UNEXPECTED_RESULT,
        "pgoutput sent $what."
    )
}

/** Reads a pgoutput message front to back. */
private class Reader(val data: ByteArray) {
    var position = 0
        private set

    fun byte(): Byte = data[position++]

    fun char(): Char = byte().toInt().toChar()

    fun short(): Short = data.getShortBE(position).also { position += 2 }

    fun int(): Int = data.getIntBE(position).also { position += 4 }

    fun long(): Long = data.getLongBE(position).also { position += 8 }

    fun cstring(): String {
        var end = position
        while (data[end] != 0.toByte()) end++
        return String(data, position, end - position, Charsets.UTF_8).also { position = end + 1 }
    }

    fun skip(count: Int) {
        position += count
    }

    fun expect(tag: Char) {
        val found = char()
        if (found != tag) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                "pgoutput sent '$found' where '$tag' belongs."
            )
        }
    }
}
