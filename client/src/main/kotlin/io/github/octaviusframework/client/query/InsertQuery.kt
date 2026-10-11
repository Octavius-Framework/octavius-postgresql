package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * Which value an identity column takes where an `INSERT` supplies one: `OVERRIDING SYSTEM VALUE` or
 * `OVERRIDING USER VALUE`.
 */
enum class OverridingValue {

    /** The value supplied, into a column `GENERATED ALWAYS` as well, which refuses one without this. */
    SYSTEM,

    /** The column's own, whatever was supplied for it - `NULL` included - being ignored. */
    USER
}

/**
 * The `ON CONFLICT` clause of an [InsertQuery], configured inside [InsertQuery.onConflict].
 *
 * A target is optional - PostgreSQL infers one for `DO NOTHING` - but an action is not, since a clause that
 * says what to conflict on and nothing about what to do is not a clause.
 */
class OnConflictClause @PublishedApi internal constructor() {

    private var target: String? = null
    private var action: String? = null

    /**
     * Conflicts on a unique index over these columns.
     *
     * @param columns The index's columns or expressions.
     * @param where The index predicate, rendered as `WHERE` after the columns. `null` or blank leaves it out.
     */
    fun onColumns(vararg columns: String, where: String? = null) {
        requireBuildable(columns.isNotEmpty()) { "ON CONFLICT on columns needs at least one column." }
        target = buildString {
            append('(').append(columns.joinToString(", ")).append(')')
            if (!where.isNullOrBlank()) append(" WHERE ").append(where)
        }
    }

    /** Conflicts on a named constraint. */
    fun onConstraint(constraintName: String) {
        requireBuildable(constraintName.isNotBlank()) { "ON CONFLICT ON CONSTRAINT needs a constraint name." }
        target = "ON CONSTRAINT $constraintName"
    }

    /** Leaves the existing row alone and inserts nothing. */
    fun doNothing() {
        action = "DO NOTHING"
    }

    /**
     * Updates the existing row.
     *
     * The assignment is SQL, so the values on the right come from `excluded` - PostgreSQL's name for the row
     * that could not be inserted - or from the target table, or from anywhere else an expression may look:
     * `doUpdate("cognomen = excluded.cognomen, updated_at = now()")`.
     *
     * @param setExpression The `SET` body, written out.
     * @param whereCondition An optional condition on the update, which is how a conflict resolves to "update
     * only where it is worth updating".
     */
    fun doUpdate(setExpression: String, whereCondition: String? = null) {
        requireBuildable(setExpression.isNotBlank()) { "ON CONFLICT DO UPDATE needs something to set." }
        action = buildString {
            append("DO UPDATE SET ").append(setExpression)
            if (!whereCondition.isNullOrBlank()) append(" WHERE ").append(whereCondition)
        }
    }

    /** As [doUpdate], with the assignments given as column-to-expression pairs. */
    fun doUpdate(vararg setPairs: Pair<String, String>, whereCondition: String? = null) {
        requireBuildable(setPairs.isNotEmpty()) { "ON CONFLICT DO UPDATE needs something to set." }
        doUpdate(setPairs.joinToString(", ") { (column, expression) -> "$column = $expression" }, whereCondition)
    }

    /** As [doUpdate], with the assignments given as a column-to-expression map. */
    fun doUpdate(setMap: Map<String, String>, whereCondition: String? = null) {
        requireBuildable(setMap.isNotEmpty()) { "ON CONFLICT DO UPDATE needs something to set." }
        doUpdate(setMap.entries.joinToString(", ") { (column, expression) -> "$column = $expression" }, whereCondition)
    }

    internal fun render(): String {
        val chosen = action
        requireBuildable(chosen != null) {
            "ON CONFLICT was opened but no action was chosen; call doNothing() or doUpdate()."
        }
        return buildString {
            append("\nON CONFLICT")
            target?.let { append(' ').append(it) }
            append(' ').append(chosen)
        }
    }

    internal fun copyInto(other: OnConflictClause) {
        other.target = target
        other.action = action
    }
}

/**
 * An `INSERT` under construction.
 *
 * Its one real job is the pair of lists that have to match: the columns, and the values in the same order.
 * Declaring a column with [value] or [values] puts a `@name` placeholder in the second list for it, so the two
 * cannot drift, and the values themselves are supplied at the terminal like every other parameter. Where a
 * value is not a parameter but an expression - `now()`, `DEFAULT`, a subselect - [valueExpression] puts that
 * there instead.
 *
 * ```kotlin
 * val id = db.insertInto("citizens")
 *     .values(listOf("cognomen", "tribe"))
 *     .valueExpression("enrolled_at", "now()")
 *     .onConflict {
 *         onColumns("cognomen")
 *         doUpdate("tribe = excluded.tribe")
 *     }
 *     .returning("id")
 *     .fetchFieldStrict<Int>("cognomen" to "Marcus", "tribe" to "Cornelia")
 * ```
 */
class InsertQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val table: String
) : RunnableQuery<InsertQuery>(provider) {

    private val cte = CteClause()
    private val assignments = LinkedHashMap<String, String>()
    private val targetColumns = mutableListOf<String>()
    private var selectSource: String? = null
    private var overridingValue: OverridingValue? = null
    private var conflict: OnConflictClause? = null
    private var returningColumns: String? = null

    /** Adds a common table expression. Call it more than once for more than one. */
    fun with(name: String, query: String): InsertQuery = apply { cte.add(name, query) }

    /** Marks the `WITH` clause `RECURSIVE`. */
    fun recursive(): InsertQuery = apply { cte.recursive = true }

    /** Declares one column, taking its value from the `@column` parameter. */
    fun value(column: String): InsertQuery = apply { addAssignment(column, "@$column") }

    /** Declares columns, each taking its value from the parameter of the same name. */
    fun values(columns: List<String>): InsertQuery = apply { columns.forEach { addAssignment(it, "@$it") } }

    /**
     * Declares columns from a map's keys, each taking its value from the parameter of the same name.
     *
     * **The values in the map are not read.** Only the keys are, and the map is passed again at the terminal
     * to supply the values - which is what keeps a query free of the parameters it names, so its
     * [toSql] can be embedded in a larger statement. Passing the same map twice is the design:
     * `insertInto("t").values(row).update(row)`.
     */
    fun values(data: Map<String, Any?>): InsertQuery = apply { values(data.keys.toList()) }

    /** Declares one column taking a SQL expression rather than a parameter - `now()`, `DEFAULT`, a subselect. */
    fun valueExpression(column: String, expression: String): InsertQuery = apply { addAssignment(column, expression) }

    /** As [valueExpression], for several columns at once. */
    fun valuesExpressions(expressions: Map<String, String>): InsertQuery = apply {
        expressions.forEach { (column, expression) -> addAssignment(column, expression) }
    }

    /**
     * Names the target columns for an `INSERT … SELECT`, where there are no values to declare.
     *
     * Only for use with [fromSelect]; the `VALUES` forms declare their columns themselves.
     */
    fun columns(vararg columns: String): InsertQuery = apply { targetColumns.addAll(columns) }

    /**
     * Inserts the rows a `SELECT` produces instead of a `VALUES` list.
     *
     * @param query The `SELECT`, written out. [RunnableQuery.toSql] of another builder fits here.
     */
    fun fromSelect(query: String): InsertQuery = apply {
        requireBuildable(assignments.isEmpty()) {
            "An INSERT takes its rows from VALUES or from a SELECT, not from both."
        }
        selectSource = query
    }

    /**
     * Adds `OVERRIDING SYSTEM VALUE` or `OVERRIDING USER VALUE`, to the `VALUES` form and to [fromSelect] alike.
     * `null` leaves it out.
     */
    fun overriding(value: OverridingValue?): InsertQuery = apply { overridingValue = value }

    /** Configures the `ON CONFLICT` clause. */
    fun onConflict(config: OnConflictClause.() -> Unit): InsertQuery = apply {
        conflict = OnConflictClause().apply(config)
    }

    /** Adds a `RETURNING` clause, which turns this into a query the `fetch*` family can read. */
    fun returning(vararg columns: String): InsertQuery = apply {
        requireBuildable(columns.isNotEmpty()) { "RETURNING needs at least one column." }
        returningColumns = columns.joinToString(", ")
    }

    /** Returns an independent copy, so that variants can be built from a shared base. */
    fun copy(): InsertQuery = InsertQuery(queryProvider, table).also {
        it.copyConvertersFrom(this)
        it.cte.copyFrom(cte)
        it.assignments.putAll(assignments)
        it.targetColumns.addAll(targetColumns)
        it.selectSource = selectSource
        it.overridingValue = overridingValue
        it.conflict = conflict?.let { source -> OnConflictClause().also { copy -> source.copyInto(copy) } }
        it.returningColumns = returningColumns
    }

    private fun addAssignment(column: String, expression: String) {
        requireBuildable(column.isNotBlank()) { "A column being inserted needs a name." }
        requireBuildable(selectSource == null) {
            "An INSERT takes its rows from VALUES or from a SELECT, not from both."
        }
        assignments[column] = expression
    }

    override fun querySql(): String {
        requireBuildable(table.isNotBlank()) { "An INSERT needs a table." }

        val source = selectSource
        val overriding = overridingValue?.let { " OVERRIDING ${it.name} VALUE" }.orEmpty()
        return buildString {
            append(cte.render())
            append("INSERT INTO ").append(table)

            if (source != null) {
                if (targetColumns.isNotEmpty()) append(" (").append(targetColumns.joinToString(", ")).append(')')
                append(overriding)
                append('\n').append(source)
            } else {
                requireBuildable(assignments.isNotEmpty()) {
                    "An INSERT needs columns; declare them with value(), values() or valueExpression(), " +
                        "or take the rows from a SELECT with fromSelect()."
                }
                append(" (").append(assignments.keys.joinToString(", ")).append(')')
                append(overriding)
                append("\nVALUES (").append(assignments.values.joinToString(", ")).append(')')
            }

            conflict?.let { append(it.render()) }
            appendClause("RETURNING", returningColumns)
        }
    }
}
