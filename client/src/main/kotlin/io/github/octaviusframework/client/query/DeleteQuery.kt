package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * A `DELETE` under construction. A `WHERE` is required; emptying a table is written with
 * [rawQuery][io.github.octaviusframework.client.OctaviusClient.rawQuery].
 *
 * ```kotlin
 * val removed = db.deleteFrom("expired_mandates")
 *     .where("expires_at < @cutoff")
 *     .returning("id")
 *     .fetchFields<Int>("cutoff" to cutoff)
 * ```
 */
class DeleteQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val table: String
) : RunnableQuery<DeleteQuery>(provider) {

    private val cte = CteClause()
    private var usingClause: String? = null
    private var whereCondition: String? = null
    private var returningColumns: String? = null

    /** Adds a common table expression. Call it more than once for more than one. */
    fun with(name: String, query: String): DeleteQuery = apply { cte.add(name, query) }

    /** Marks the `WITH` clause `RECURSIVE`. */
    fun recursive(): DeleteQuery = apply { cte.recursive = true }

    /** Sets the `USING` clause, for a delete that joins against other tables. */
    fun using(tables: String): DeleteQuery = apply { usingClause = tables }

    /** Sets the `WHERE` condition. Required. */
    fun where(condition: String): DeleteQuery = apply { whereCondition = condition }

    /** Adds a `RETURNING` clause, which turns this into a query the `fetch*` family can read. */
    fun returning(vararg columns: String): DeleteQuery = apply {
        requireBuildable(columns.isNotEmpty()) { "RETURNING needs at least one column." }
        returningColumns = columns.joinToString(", ")
    }

    /** Returns an independent copy, so that variants can be built from a shared base. */
    fun copy(): DeleteQuery = DeleteQuery(queryProvider, table).also {
        it.copyConvertersFrom(this)
        it.cte.copyFrom(cte)
        it.usingClause = usingClause
        it.whereCondition = whereCondition
        it.returningColumns = returningColumns
    }

    override fun querySql(): String {
        requireBuildable(table.isNotBlank()) { "A DELETE needs a table." }
        requireBuildable(!whereCondition.isNullOrBlank()) {
            "A DELETE built here requires a WHERE. To empty '$table', write the statement with rawQuery() " +
                "so that it says so."
        }

        return buildString {
            append(cte.render())
            append("DELETE FROM ").append(table)
            appendClause("USING", usingClause)
            appendClause("WHERE", whereCondition)
            appendClause("RETURNING", returningColumns)
        }
    }
}
