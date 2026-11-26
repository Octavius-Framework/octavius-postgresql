package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.message.backend.FieldDescription
import io.github.octaviusframework.driver.row.RowMetadata

/**
 * A published table, as pgoutput describes it ahead of its changes: its [oid], [schema] and [name], and the
 * [keyColumns] its [replicaIdentity] names, which are every column under [ReplicaIdentity.FULL].
 */
class PgRelation internal constructor(
    val oid: Int,
    val schema: String,
    val name: String,
    val replicaIdentity: ReplicaIdentity,
    val keyColumns: List<String>,
    /** The published columns, as the rows of the table's changes carry them. */
    internal val metadata: RowMetadata,
    /** The published columns as described, for the rows that carry only some of them. */
    internal val fields: List<FieldDescription>,
    /** Which of [metadata]'s columns are in [keyColumns]. */
    internal val isKey: BooleanArray,
    /** The [keyColumns] alone, as a row of the old key carries them. */
    internal val keyMetadata: RowMetadata
) {
    override fun toString(): String = "PgRelation($schema.$name)"
}
