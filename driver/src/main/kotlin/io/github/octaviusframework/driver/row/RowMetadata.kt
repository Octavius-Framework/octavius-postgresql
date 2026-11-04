package io.github.octaviusframework.driver.row

import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.message.backend.FieldDescription
import io.github.octaviusframework.driver.registry.TypeDictionary

/**
 * The columns of a result, in order, resolved once for the whole result and shared by its rows. A column of a
 * type the catalog does not describe fails here, with
 * [TypeException][io.github.octaviusframework.driver.exception.TypeException], whether or not a row carries a value.
 */
class RowMetadata internal constructor(
    fields: List<FieldDescription>,
    dictionary: TypeDictionary
) {
    /** Every column, in order. */
    val columns: List<ColumnMetadata> = fields.map { it.resolve(dictionary) }

    /** The number of columns. */
    val size: Int get() = columns.size

    /** The column names, in order. */
    val columnNames: List<String> = columns.map { it.name }

    private val nameToIndexCache: Map<String, Int>

    init {
        val map = HashMap<String, Int>()
        columns.forEachIndexed { index, column ->
            map.putIfAbsent(column.name, index)
        }
        nameToIndexCache = map
    }

    /**
     * The zero-based index of the first column named [columnName].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if there is no such column.
     */
    fun getColumnIndex(columnName: String): Int {
        return nameToIndexCache[columnName] ?: throw MappingException(MappingExceptionReason.COLUMN_NOT_FOUND, "Column not found: $columnName")
    }

    /** Whether this result has a column named [columnName]. */
    fun hasColumn(columnName: String): Boolean = nameToIndexCache.containsKey(columnName)

    /**
     * The column at zero-based [index].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is out of bounds.
     */
    fun getColumn(index: Int): ColumnMetadata {
        if (index !in columns.indices) throw MappingException(MappingExceptionReason.COLUMN_NOT_FOUND, "Column index out of bounds: $index")
        return columns[index]
    }

    /**
     * The first column named [columnName].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if there is no such column.
     */
    fun getColumn(columnName: String): ColumnMetadata = columns[getColumnIndex(columnName)]

    /**
     * The type OID of the column at zero-based [index].
     *
     * @throws MappingException `COLUMN_NOT_FOUND` if [index] is out of bounds.
     */
    fun getOid(index: Int): Int = getColumn(index).oid
}

/**
 * Resolves a `RowDescription` field against [dictionary]. The type has to be there; an origin the dictionary does
 * not describe keeps only its OIDs.
 */
private fun FieldDescription.resolve(dictionary: TypeDictionary): ColumnMetadata {
    val origin = if (tableOid == 0) null else {
        val relation = dictionary.findCompositeByRelation(tableOid)
        val attributeNumber = columnAttrNumber.toInt()
        ColumnOrigin(
            relationOid = tableOid,
            attributeNumber = attributeNumber,
            relationName = relation?.name,
            schema = relation?.schema,
            columnName = relation?.attributeNameByNumber(attributeNumber)
        )
    }
    return ColumnMetadata(name, dictionary.getPgType(dataTypeOid), typeModifier, origin)
}
