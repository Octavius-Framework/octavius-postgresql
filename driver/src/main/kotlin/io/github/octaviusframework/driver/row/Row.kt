package io.github.octaviusframework.driver.row

import io.github.octaviusframework.driver.codec.decodeSafely
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.TypeException
import io.github.octaviusframework.driver.exception.TypeExceptionReason
import io.github.octaviusframework.driver.registry.TypeCatalog
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Represents a single row returned from a query execution.
 *
 * `Row` holds the decoded raw data for each column and provides methods to access
 * these values, either in their raw form or mapped to a specific Kotlin type
 * using the configured [ResultMapper].
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
            val oid = metadata.columns[index].oid
            val codec = catalog.codecs.getCodecByOid<Any>(oid)
                ?: throw TypeException(TypeExceptionReason.MISSING_CODEC, oid = oid, details = "Row")
            codec.decodeSafely(rawData, offset, colLength)
        }
    }

    /**
     * A list containing the names of all columns in this row, in order.
     */
    val columnNames: List<String>
        get() = metadata.columnNames

    /**
     * Retrieves the value of the column at the specified [index], mapped to the specified [targetType].
     *
     * @param T The expected type of the returned value.
     * @param index The zero-based index of the column.
     * @param targetType The Kotlin reflection type representing the desired output type.
     * @return The mapped column value.
     * @throws MappingException if the column cannot be mapped to the requested type.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(index: Int, targetType: KType): T {
        val raw = getRaw(index)
        val column = metadata.getColumn(index)
        try {
            return resultMapper.deserialize(raw, targetType, sourceType = column.type) as T
        } catch (e: OctaviusException) {
            // The column's name goes on the path, as it does when a row is mapped by `fetchObjects` or read
            // as a map - the same failure should read the same way whichever route reached it. Added here
            // rather than passed to the mapper because `deserialize` is called from inline functions, so its
            // signature reaches user bytecode and is not ours to widen.
            e.path.add(column.name)
            throw e
        }
    }

    /**
     * Retrieves the value of the column at the specified [index], mapped to the reified type [T].
     *
     * Also written `row[0]`, where the expected type is what fixes [T] - `val id: Int = row[0]`.
     *
     * @param T The expected type of the returned value.
     * @param index The zero-based index of the column.
     * @return The mapped column value.
     * @throws MappingException if the column cannot be mapped to the requested type.
     */
    inline operator fun <reified T> get(index: Int): T {
        return get(index, typeOf<T>())
    }

    /**
     * Retrieves the value of the column with the specified [columnName], mapped to the specified [targetType].
     *
     * @param T The expected type of the returned value.
     * @param columnName The name of the column.
     * @param targetType The Kotlin reflection type representing the desired output type.
     * @return The mapped column value.
     * @throws MappingException if the column is not found or cannot be mapped.
     */
    fun <T> get(columnName: String, targetType: KType): T {
        return get(getColumnIndex(columnName), targetType)
    }

    /**
     * Retrieves the value of the column with the specified [columnName], mapped to the reified type [T].
     *
     * Also written `row["cognomen"]`, where the expected type is what fixes [T] - and its nullability with
     * it, exactly as in the `fetch*` family: `val name: String = row["cognomen"]` refuses a `NULL`,
     * `val name: String? = row["cognomen"]` accepts one.
     *
     * @param T The expected type of the returned value.
     * @param columnName The name of the column.
     * @return The mapped column value.
     * @throws MappingException if the column is not found or cannot be mapped.
     */
    inline operator fun <reified T> get(columnName: String): T {
        return get(getColumnIndex(columnName), typeOf<T>())
    }

    /**
     * Retrieves the zero-based index of the column with the given [columnName].
     *
     * @param columnName The name of the column to find.
     * @return The zero-based index of the column.
     * @throws MappingException if the column name does not exist in this row.
     */
    fun getColumnIndex(columnName: String): Int {
        return metadata.getColumnIndex(columnName)
    }

    /**
     * Whether this row has a column called [columnName].
     *
     * What [getColumnIndex] answers by raising. For a result whose shape is not fixed - a projection
     * assembled at runtime, a `RETURNING` that differs by branch - asking is what the code means, and a
     * `try`/`catch` around a lookup is not.
     *
     * @param columnName The name to look for.
     * @return `true` where a column carries that name.
     */
    fun hasColumn(columnName: String): Boolean = metadata.hasColumn(columnName)

    /**
     * Retrieves the raw, decoded database value for the column at the specified [index].
     *
     * @param index The zero-based index of the column.
     * @return The raw value (its type depends on the registered codec for the column's OID).
     * @throws MappingException if the index is out of bounds.
     */
    fun getRaw(index: Int): Any? {
        if (index !in values.indices) throw MappingException(MappingExceptionReason.COLUMN_NOT_FOUND, "Column index out of bounds: $index")
        return values[index]
    }

    /**
     * Retrieves the raw, decoded database value for the column with the specified [columnName].
     *
     * @param columnName The name of the column.
     * @return The raw value (its type depends on the registered codec for the column's OID).
     * @throws MappingException if the column name does not exist in this row.
     */
    fun getRaw(columnName: String): Any? = getRaw(getColumnIndex(columnName))

    /**
     * Retrieves the PostgreSQL Object Identifier (OID) of the type for the column at the specified [index].
     *
     * @param index The zero-based index of the column.
     * @return The OID of the column's type.
     */
    fun getOid(index: Int): Int {
        return metadata.getOid(index)
    }
}
