package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * SQL written by hand, with `@name` parameters and the terminals every builder has, for a statement better written
 * whole. Its SQL comes back out of [toSql].
 *
 * It alone has [execute], for DDL, `SET` and several statements in one round trip.
 */
class RawQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val sql: String
) : RunnableQuery<RawQuery>(provider) {

    override fun querySql(): String = sql

    /**
     * Runs the SQL and discards what it produced - DDL, `SET`, several statements separated by `;` in one round
     * trip, which PostgreSQL wraps in an implicit transaction.
     *
     * It speaks the Simple Query Protocol, which binds nothing: an `@name` reaches the server as literal text. A
     * hand-written `INSERT`, `UPDATE` or `DELETE` goes through [executeUpdate][RunnableQuery.executeUpdate].
     *
     * @param ignoreRows Whether a statement returning rows may pass; the rows are discarded either way. Set it for
     * a script where a `SELECT` is legitimate, as `pg_dump`'s `SELECT pg_catalog.setval(...)` is.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `UNEXPECTED_RESULT` where a
     * statement returned rows and [ignoreRows] is `false`.
     */
    fun execute(ignoreRows: Boolean = false) {
        queryProvider.execute { createNamedQuery(sql).execute(ignoreRows) }
    }
}
