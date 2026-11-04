package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * A `SELECT` under construction. Every clause takes SQL and passes it through; the builder supplies the keywords
 * and their order, and leaves out a clause given `null` or blank, so a filter assembled at runtime can go
 * straight in.
 *
 * ```kotlin
 * val senators = db.select("id", "cognomen", "province_id")
 *     .from("senate")
 *     .where(filter.sql)          // null or empty leaves out the WHERE entirely
 *     .orderBy("cognomen")
 *     .page(page = 0, size = 20)
 *     .fetchObjects<Senator>(filter.params)
 * ```
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
    private var windowClause: String? = null
    private var orderByClause: String? = null
    private var limitValue: Long? = null
    private var offsetValue: Long? = null
    private var lockingClause: String? = null

    /** Adds a common table expression. Call it more than once for more than one. */
    fun with(name: String, query: String): SelectQuery = apply { cte.add(name, query) }

    /** Marks the `WITH` clause `RECURSIVE`. */
    fun recursive(): SelectQuery = apply { cte.recursive = true }

    /**
     * Sets the `FROM` clause, passed through as written: `"legions l JOIN provinces p ON l.province_id = p.id"`,
     * `"UNNEST(@ids) AS id"`, `"calculate_tribute(@province, @year)"`.
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

    /**
     * Sets the `WINDOW` clause, as SQL: the named windows the select list and `ORDER BY` refer to,
     * `"w AS (PARTITION BY province ORDER BY strength DESC)"`. `null` or blank leaves the clause out.
     */
    fun window(definitions: String?): SelectQuery = apply { windowClause = definitions }

    /** Sets the `ORDER BY` clause. `null` or blank leaves the clause out. */
    fun orderBy(ordering: String?): SelectQuery = apply { orderByClause = ordering }

    /** Sets `LIMIT`. `null` leaves it out. */
    fun limit(count: Long?): SelectQuery = apply { limitValue = count }

    /** Sets `OFFSET`. `null` leaves it out, as does `0`. */
    fun offset(position: Long?): SelectQuery = apply {
        requireBuildable(position == null || position >= 0) { "OFFSET cannot be negative, and was $position." }
        offsetValue = position
    }

    /** Sets `LIMIT` and `OFFSET` for [page], counted from zero, of [size] rows. */
    fun page(page: Long, size: Long): SelectQuery = apply {
        requireBuildable(page >= 0) { "A page number cannot be negative, and was $page." }
        requireBuildable(size > 0) { "A page size has to be positive, and was $size." }
        offsetValue = page * size
        limitValue = size
    }

    /**
     * Sets the locking clause, `FOR` included, since several may follow one another: `"FOR UPDATE"`,
     * `"FOR UPDATE OF l FOR SHARE OF p NOWAIT"`. `null` or blank leaves it out. Outside a transaction the lock
     * ends with the statement.
     */
    fun locking(clause: String?): SelectQuery = apply { lockingClause = clause }

    /** Returns an independent copy, so that variants can be built from a shared base. */
    fun copy(): SelectQuery = SelectQuery(queryProvider, selectClause).also {
        it.copyConvertersFrom(this)
        it.cte.copyFrom(cte)
        it.fromClause = fromClause
        it.whereCondition = whereCondition
        it.groupByClause = groupByClause
        it.havingClause = havingClause
        it.windowClause = windowClause
        it.orderByClause = orderByClause
        it.limitValue = limitValue
        it.offsetValue = offsetValue
        it.lockingClause = lockingClause
    }

    /**
     * A query counting the rows this one matches across every page, for the total a paged list shows.
     *
     * ```kotlin
     * val listed = db.select("id", "cognomen").from("senate").where(filter.sql).orderBy("cognomen")
     *
     * val total = listed.toCountQuery().fetchFieldStrict<Long>(filter.params)
     * val senators = listed.page(page, 20).fetchObjects<Senator>(filter.params)
     * ```
     *
     * This query is wrapped whole, `SELECT count(*) FROM (…)`, so a `DISTINCT` or a `GROUP BY` counts the rows it
     * returns. Its `ORDER BY`, `LIMIT`, `OFFSET` and locking clause are left out - a `LIMIT` that belongs to the
     * meaning, a top ten, along with them.
     *
     * The count takes this query's parameters and parameter converters, not its result converters. It is rendered
     * now, so later changes to this builder do not reach it.
     */
    fun toCountQuery(): SelectQuery {
        val counted = copy().also {
            it.orderByClause = null
            it.limitValue = null
            it.offsetValue = null
            it.lockingClause = null
        }
        return SelectQuery(queryProvider, "count(*)").fromSubquery(counted.querySql()).also {
            it.queryParameterConverters = queryParameterConverters?.toMutableList()
        }
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
            appendClause("WINDOW", windowClause)
            appendClause("ORDER BY", orderByClause)
            limitValue?.let { append("\nLIMIT ").append(it) }
            offsetValue?.takeIf { it > 0 }?.let { append("\nOFFSET ").append(it) }
            lockingClause?.takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        }
    }
}
