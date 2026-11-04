package io.github.octaviusframework.driver.message.backend

/**
 * One column of a `RowDescription`, resolved into a
 * [ColumnMetadata][io.github.octaviusframework.driver.row.ColumnMetadata] once per result.
 *
 * @property tableOid The relation the column reads, or `0` for anything but a plain column.
 * @property columnAttrNumber The column's number in that relation, or `0` for anything but a plain column.
 */
internal class FieldDescription(
    val name: String,
    val tableOid: Int,
    val columnAttrNumber: Short,
    val dataTypeOid: Int,
    val dataTypeSize: Short,
    val typeModifier: Int,
    val formatCode: Short
)
