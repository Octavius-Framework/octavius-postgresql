package io.github.octaviusframework.driver.row

import io.github.octaviusframework.driver.type.PgType

/**
 * One column of a result, resolved against the catalog its query ran under.
 *
 * @property name The name the column came back under: its alias, or the source column's name.
 * @property type The column's type. Never a [PgType.Domain]: the server describes a column over a domain as the
 *   base type.
 * @property typeModifier The raw `atttypmod`, carrying what a `numeric(10,2)` or a `varchar(64)` declares; `-1`
 *   where the type takes none.
 * @property origin The column of a relation this one reads, or `null` for an expression, a literal or a function.
 */
class ColumnMetadata internal constructor(
    val name: String,
    val type: PgType,
    val typeModifier: Int,
    val origin: ColumnOrigin?
) {
    /** The OID of the column's type. */
    val oid: Int get() = type.oid
}

/**
 * The relation and column a result column was read from. The names are `null` for a relation the type catalog
 * does not describe: `pg_catalog`, `information_schema`, a temporary table, or one created since the last load.
 *
 * @property relationOid The OID of the table, view or materialized view.
 * @property attributeNumber The column's number in the relation; negative for a system column such as `ctid`.
 * @property columnName The column's name in the relation, before any alias. `null` also for a system column.
 */
class ColumnOrigin internal constructor(
    val relationOid: Int,
    val attributeNumber: Int,
    val relationName: String?,
    val schema: String?,
    val columnName: String?
)
