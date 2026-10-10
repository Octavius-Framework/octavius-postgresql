package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * A `SELECT` under construction.
 *
 * Every clause takes SQL and passes it through: `from("legions l JOIN provinces p ON l.province_id = p.id")`
 * is written out because that is the join, not because the builder has a way to describe joins. What the
 * builder does is the mechanical part - the keywords, the order they go in, and above all the clauses that
 * disappear when they have nothing to say, which is what makes a filter assembled at runtime bearable.
 *
 * ```kotlin
 * val senators = db.select("id", "cognomen", "province_id")
 *     .from("senate")
 *     .where(filter.sql)          // null or empty leaves out the WHERE entirely
 *     .orderBy("cognomen")
 *     .page(page = 0, size = 20)
 *     .fetchObjects<Senator>(filter.params)
 * ```
 *
 * The terminal methods come from [RunnableQuery], so parameters are supplied there and never carried here.
 */
class SelectQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val selectClause: String
) : RunnableQuery<SelectQuery>(provider) {

    private val cte = CteClause()
    private var fromClause: String? = null
    private var whereCondition: String? = null
    private var groupByClause: String? = null
    private var havingClause: String? = null
    private var orderByClause: String? = null
    private var limitValue: Long? = null
    private var offsetValue: Long? = null
    private var lockingClause: String? = null

    /** Adds a common table expression. Call it more than once for more than one. */
    fun with(name: String, query: String): SelectQuery = apply { cte.add(name, query) }

    /** Marks the `WITH` clause `RECURSIVE`. */
    fun recursive(): SelectQuery = apply { cte.recursive = true }

    /**
     * Sets the `FROM` clause, as SQL.
     *
     * Whatever PostgreSQL accepts after `FROM` goes here and is passed through untouched: `"legions"`,
     * `"legions l"`, `"legions l JOIN provinces p ON l.province_id = p.id"`, `"UNNEST(@ids) AS id"`, or a
     * function call such as `"calculate_tribute(@province, @year)"`.
     */
    fun from(source: String): SelectQuery = apply { fromClause = source }

    /** Sets the `FROM` clause to a subquery, parenthesised and optionally aliased. */
    fun fromSubquery(subquery: String, alias: String? = null): SelectQuery = apply {
        fromClause = "($subquery)" + (alias?.let { " AS $it" } ?: "")
    }

    /** Sets the `WHERE` condition. `null` or blank leaves the clause out. */
    fun where(condition: String?): SelectQuery = apply { whereCondition = condition }

    /** Sets the `GROUP BY` columns. `null` or blank leaves the clause out. */
    fun groupBy(columns: String?): SelectQuery = apply { groupByClause = columns }

    /** Sets the `HAVING` condition, which requires a `GROUP BY`. `null` or blank leaves the clause out. */
    fun having(condition: String?): SelectQuery = apply { havingClause = condition }

    /** Sets the `ORDER BY` clause. `null` or blank leaves the clause out. */
    fun orderBy(ordering: String?): SelectQuery = apply { orderByClause = ordering }

    /** Sets `LIMIT`. `null` leaves it out. */
    fun limit(count: Long?): SelectQuery = apply { limitValue = count }

    /** Sets `OFFSET`. `null` leaves it out, as does `0`. */
    fun offset(position: Long?): SelectQuery = apply {
        requireBuildable(position == null || position >= 0) { "OFFSET cannot be negative, and was $position." }
        offsetValue = position
    }

    /**
     * Sets `LIMIT` and `OFFSET` together from a page number and a page size.
     *
     * @param page The page, counted from zero.
     * @param size How many rows a page holds.
     */
    fun page(page: Long, size: Long): SelectQuery = apply {
        requireBuildable(page >= 0) { "A page number cannot be negative, and was $page." }
        requireBuildable(size > 0) { "A page size has to be positive, and was $size." }
        offsetValue = page * size
        limitValue = size
    }

    /**
     * Sets the locking clause, as SQL. `null` or blank leaves it out.
     *
     * Written whole, `FOR` included, since several may follow one another: `"FOR UPDATE"`,
     * `"FOR NO KEY UPDATE OF l SKIP LOCKED"`, `"FOR UPDATE OF l FOR SHARE OF p NOWAIT"`.
     *
     * Only meaningful inside a transaction - the lock is held until it ends, and outside one that is until the
     * statement finishes, which is no lock at all.
     */
    fun locking(clause: String?): SelectQuery = apply { lockingClause = clause }

    /**
     * Returns an independent copy, so that variants can be built from a shared base.
     *
     * ```kotlin
     * val base = db.select("*").from("legions").orderBy("name")
     * val onMarch = base.copy().where("status = 'ON_MARCH'")
     * val garrisoned = base.copy().where("status = 'GARRISONED'")
     * ```
     */
    fun copy(): SelectQuery = SelectQuery(queryProvider, selectClause).also {
        it.copyConvertersFrom(this)
        it.cte.copyFrom(cte)
        it.fromClause = fromClause
        it.whereCondition = whereCondition
        it.groupByClause = groupByClause
        it.havingClause = havingClause
        it.orderByClause = orderByClause
        it.limitValue = limitValue
        it.offsetValue = offsetValue
        it.lockingClause = lockingClause
    }

    override fun querySql(): String {
        requireBuildable(selectClause.isNotBlank()) { "A SELECT needs at least one column." }
        requireBuildable(
            !fromClause.isNullOrBlank() ||
                (whereCondition.isNullOrBlank() && groupByClause.isNullOrBlank() && orderByClause.isNullOrBlank())
        ) { "WHERE, GROUP BY and ORDER BY have nothing to apply to without a FROM clause." }
        requireBuildable(havingClause.isNullOrBlank() || !groupByClause.isNullOrBlank()) {
            "HAVING filters groups, so it needs a GROUP BY."
        }

        return buildString {
            append(cte.render())
            append("SELECT ").append(selectClause)
            appendClause("FROM", fromClause)
            appendClause("WHERE", whereCondition)
            appendClause("GROUP BY", groupByClause)
            appendClause("HAVING", havingClause)
            appendClause("ORDER BY", orderByClause)
            limitValue?.let { append("\nLIMIT ").append(it) }
            offsetValue?.takeIf { it > 0 }?.let { append("\nOFFSET ").append(it) }
            lockingClause?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        }
    }
}
