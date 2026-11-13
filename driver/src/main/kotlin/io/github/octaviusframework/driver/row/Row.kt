package io.github.octaviusframework.driver.row

import io.github.octaviusframework.driver.codec.decodeSafely
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.registry.TypeCatalog
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * One row of a result, its columns decoded by their codecs and converted to the type asked for by `get`, against
 * the catalog its query ran under.
 */
class Row internal constructor(
    rawData: ByteArray,
    columnOffsets: IntArray,
    columnLengths: IntArray,
    val metadata: RowMetadata,
    private val catalog: TypeCatalog,
    private val resultMapper: ResultMapper
) {

    private val values: List<Any?> = List(metadata.size) { index ->
        val colLength = columnLengths[index]
        if (colLength == -1) null
        else {
            val offset = columnOffsets[index]
            val column = metadata.columns[index]
            try {
                val codec = catalog.codecs.getCodecByOid<Any>(column.oid)
                    ?: throw TypeException(TypeExceptionReason.MISSING_CODEC, oid = column.oid, details = "Row")
                codec.decodeSafely(rawData, offset, colLength)
            } catch (e: OctaviusException) {
                // Decoded here, ahead of any `get`, so the column is named here as well as there.
                e.path.add(column.name)
                throw e
            }
        }
    }

    /** The column names, in order. */
    val columnNames: List<String>
        get() = metadata.columnNames

    /**
     * Returns the column at zero-based [index], converted to [targetType].
     *
     * @throws MappingException if the column cannot be mapped to [targetType].
     */
    fun <T> get(index: Int, targetType: KType): T {
        val raw = getRaw(index)
        val column = metadata.getColumn(index)
        return resultMapper.deserialize(raw, targetType, column.type, column.name)
    }

    /**
     * Returns the column at zero-based [index], converted to [T], which has to be nullable to accept a `NULL`. In
     * `val id: Int = row[0]` the expected type fixes [T].
     *
     * @throws MappingException if the column cannot be mapped to [T].
     */
    inline operator fun <reified T> get(index: Int): T {
        return get(index, typeOf<T>())
    }

    /**
     * Returns the column named [columnName], converted to [targetType].
     *
     * @throws MappingException if there is no such column, or it cannot be mapped.
     */
    fun <T> get(columnName: String, targetType: KType): T {
        return get(getColumnIndex(columnName), targetType)
    }

    /**
     * Returns the column named [columnName], converted to [T], which has to be nullable to accept a `NULL`. In
     * `val name: String? = row["cognomen"]` the expected type fixes [T].
     *
     * @throws MappingException if there is no such column, or it cannot be mapped.
     */
    inline operator fun <reified T> get(columnName: String): T {
        return get(getColumnIndex(columnName), typeOf<T>())
    }

    /**
     * The zero-based index of the first column named [columnName].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if there is no such column.
     */
    fun getColumnIndex(columnName: String): Int {
        return metadata.getColumnIndex(columnName)
    }

    /** Whether this row has a column named [columnName]. */
    fun hasColumn(columnName: String): Boolean = metadata.hasColumn(columnName)

    /**
     * Returns the column at zero-based [index] as its codec decoded it, with no converter run.
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is out of bounds.
     */
    fun getRaw(index: Int): Any? {
        if (index !in values.indices) throw MappingException(MappingExceptionReason.COLUMN_NOT_FOUND, "Column index out of bounds: $index")
        return values[index]
    }

    /**
     * Returns the column named [columnName] as its codec decoded it, with no converter run.
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if there is no such column.
     */
    fun getRaw(columnName: String): Any? = getRaw(getColumnIndex(columnName))

    /**
     * Returns this row mapped onto [targetType], as `fetchObject` maps a row of a result: a data class by column
     * names, a `Map<String, V>` with every column converted to `V`.
     *
     * @throws MappingException if the row cannot be mapped onto [targetType].
     */
    fun <T : Any> toObject(targetType: KType): T = resultMapper.deserialize(this, targetType, PgType.Record)

    /**
     * Returns this row mapped onto [T], as `fetchObject` maps a row of a result.
     *
     * @throws MappingException if the row cannot be mapped onto [T].
     */
    inline fun <reified T : Any> toObject(): T = toObject(typeOf<T>())

    /** The type OID of the column at zero-based [index]. */
    fun getOid(index: Int): Int {
        return metadata.getOid(index)
    }
}
