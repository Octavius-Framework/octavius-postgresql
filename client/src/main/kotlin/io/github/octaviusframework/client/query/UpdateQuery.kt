package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * An `UPDATE` under construction. [setValue] and [setValues] assign from `@name` parameters supplied at the
 * terminal; [setExpression] assigns SQL, which may read the column: `setExpression("quantity", "quantity - 1")`.
 *
 * A `WHERE` is required. An `UPDATE` of every row is written with
 * [rawQuery][io.github.octaviusframework.client.OctaviusClient.rawQuery].
 *
 * ```kotlin
 * db.update("legion_supplies")
 *     .setExpression("quantity", "quantity - @taken")
 *     .setValue("last_drawn_at")
 *     .where("id = @id")
 *     .executeUpdate("taken" to 1, "last_drawn_at" to now, "id" to supplyId)
 * ```
 */
class UpdateQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val table: String
) : RunnableQuery<UpdateQuery>(provider) {

    private val cte = CteClause()
    private val assignments = LinkedHashMap<String, String>()
    private var fromClause: String? = null
    private var whereCondition: String? = null
    private var returningColumns: String? = null

    /** Adds a common table expression. Call it more than once for more than one. */
    fun with(name: String, query: String): UpdateQuery = apply { cte.add(name, query) }

    /** Marks the `WITH` clause `RECURSIVE`. */
    fun recursive(): UpdateQuery = apply { cte.recursive = true }

    /** Assigns one column from the `@column` parameter. */
    fun setValue(column: String): UpdateQuery = apply { addAssignment(column, "@$column") }

    /** Assigns each column from the parameter of the same name. */
    fun setValues(columns: List<String>): UpdateQuery = apply { columns.forEach { addAssignment(it, "@$it") } }

    /**
     * Assigns the columns named by [data]'s keys, each from the parameter of the same name. The map's values are
     * not read, so pass it to the terminal too.
     */
    fun setValues(data: Map<String, Any?>): UpdateQuery = apply { setValues(data.keys.toList()) }

    /** Assigns one column from a SQL expression, which may read the column it is assigning. */
    fun setExpression(column: String, expression: String): UpdateQuery = apply { addAssignment(column, expression) }

    /** As [setExpression], for several columns at once. */
    fun setExpressions(expressions: Map<String, String>): UpdateQuery = apply {
        expressions.forEach { (column, expression) -> addAssignment(column, expression) }
    }

    /** Sets the `FROM` clause, for an update that reads from other tables. */
    fun from(tables: String): UpdateQuery = apply { fromClause = tables }

    /** Sets the `WHERE` condition. Required. */
    fun where(condition: String): UpdateQuery = apply { whereCondition = condition }

    /** Adds a `RETURNING` clause, which turns this into a query the `fetch*` family can read. */
    fun returning(vararg columns: String): UpdateQuery = apply {
        requireBuildable(columns.isNotEmpty()) { "RETURNING needs at least one column." }
        returningColumns = columns.joinToString(", ")
    }

    /** Returns an independent copy, so that variants can be built from a shared base. */
    fun copy(): UpdateQuery = UpdateQuery(queryProvider, table).also {
        it.copyConvertersFrom(this)
        it.cte.copyFrom(cte)
        it.assignments.putAll(assignments)
        it.fromClause = fromClause
        it.whereCondition = whereCondition
        it.returningColumns = returningColumns
    }

    private fun addAssignment(column: String, expression: String) {
        requireBuildable(column.isNotBlank()) { "A column being updated needs a name." }
        assignments[column] = expression
    }

    override fun querySql(): String {
        requireBuildable(table.isNotBlank()) { "An UPDATE needs a table." }
        requireBuildable(assignments.isNotEmpty()) {
            "An UPDATE needs something to set; declare it with setValue(), setValues() or setExpression()."
        }
        requireBuildable(!whereCondition.isNullOrBlank()) {
            "An UPDATE built here requires a WHERE. To update every row in '$table', write the statement " +
                "with rawQuery() so that it says so."
        }

        return buildString {
            append(cte.render())
            append("UPDATE ").append(table)
            append("\nSET ").append(assignments.entries.joinToString(", ") { (c, e) -> "$c = $e" })
            appendClause("FROM", fromClause)
            appendClause("WHERE", whereCondition)
            appendClause("RETURNING", returningColumns)
        }
    }
}
