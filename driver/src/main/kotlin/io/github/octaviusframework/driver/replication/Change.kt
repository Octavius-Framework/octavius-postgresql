package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.row.Row

/** A change a replicated transaction made: to rows of a table, or a truncate of tables. */
sealed interface Change : TransactionEntry {

    /** A change to rows of [relation], whose rows are read as the rows of a query are. */
    sealed interface OfRow : Change {
        val relation: PgRelation
    }

    /** The [new] row of [relation]. */
    class Insert internal constructor(override val relation: PgRelation, val new: Row) : OfRow {
        override fun toString(): String = "Insert($relation)"
    }

    /**
     * An update of a row of [relation] to [new], which holds every column but the [unchanged] ones. The [old] row
     * is whole under [ReplicaIdentity.FULL]; otherwise it holds only the [PgRelation.keyColumns], and only when the
     * update changed them.
     */
    class Update internal constructor(
        override val relation: PgRelation,
        val old: Row?,
        val new: Row,
        /**
         * The columns whose TOASTed values the update left as they were, which the server does not send; none
         * under [ReplicaIdentity.FULL].
         */
        val unchanged: Set<String>
    ) : OfRow {
        override fun toString(): String = "Update($relation)"
    }

    /** A delete of the [old] row of [relation]: whole under [ReplicaIdentity.FULL], otherwise its key columns. */
    class Delete internal constructor(override val relation: PgRelation, val old: Row) : OfRow {
        override fun toString(): String = "Delete($relation)"
    }

    /** A `TRUNCATE` of [relations], with `CASCADE` if [cascade] and `RESTART IDENTITY` if [restartIdentity]. */
    class Truncate internal constructor(
        val relations: List<PgRelation>,
        val cascade: Boolean,
        val restartIdentity: Boolean
    ) : Change {
        override fun toString(): String = "Truncate($relations)"
    }
}
