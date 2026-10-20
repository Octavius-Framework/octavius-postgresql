package io.github.octaviusframework.driver.row

import io.github.octaviusframework.driver.type.PgType

/**
 * What the server said about one column of a result, resolved against the type catalog the query ran under.
 *
 * Built once per `RowDescription` - once per result rather than once per row - and shared by every [Row] read
 * from it.
 *
 * @property name The name the column came back under: the alias where the query gave it one, the source column's
 *   name where it did not.
 * @property type The column's type. Resolved through the dictionary in force when the query ran, so two columns
 *   of one result always agree on the catalog they were read against. Never a [PgType.Domain]: the server
 *   resolves a domain to the type underneath it before describing a column - for a plain reference, an explicit
 *   cast and a function's result alike - so a column declared over one arrives as the base type, and only
 *   [origin] still says which column it was. A domain is still reachable *through* a type, as an array's element
 *   or a composite's attribute, where it survives.
 * @property typeModifier The server's raw `atttypmod` for the column, `-1` where the type takes no modifier. It is
 *   the one thing here with no other source - the precision and scale of a `numeric(10,2)`, the length of a
 *   `varchar(64)`, the precision of a `timestamp(3)` live nowhere else in a result - and it is left undecoded
 *   because reading it takes knowledge of the particular type.
 * @property origin Where the column was read from, when it is a plain reference to a column of a relation, and
 *   `null` when it is not: an expression, a literal, the output of a function.
 */
class ColumnMetadata internal constructor(
    val name: String,
    val type: PgType,
    val typeModifier: Int,
    val origin: ColumnOrigin?
) {
    /**
     * The OID of the column's type.
     */
    val oid: Int get() = type.oid
}

/**
 * The relation and column a result column was read from.
 *
 * That this is here at all says the server tracked the value back to a stored column. Whether the names inside it
 * are filled in says something else: they come from the relation's row type, which the driver reads when it
 * connects and on every `reloadTypes()`, so they are `null` for a relation that snapshot does not describe -
 * anything in `pg_catalog` or `information_schema` and any temporary table, which the type load skips, and
 * anything created since. The values of such a column decode as they always did; it is only the naming that
 * depends on the catalog.
 *
 * @property relationOid The OID of the relation, whatever its kind - table, view, materialized view.
 * @property attributeNumber The column's attribute number within that relation. Negative for a system column such
 *   as `ctid` or `xmin`, which no row type describes, leaving [columnName] `null`.
 * @property relationName The relation's name, or `null` when the catalog snapshot does not describe the relation.
 * @property schema The schema the relation lives in, or `null` on the same terms.
 * @property columnName The column's name in the relation, which an alias in the query may have replaced in
 *   [ColumnMetadata.name]. `null` when the relation is not described, or when its row type carries no attribute
 *   under this number.
 */
class ColumnOrigin internal constructor(
    val relationOid: Int,
    val attributeNumber: Int,
    val relationName: String?,
    val schema: String?,
    val columnName: String?
)
