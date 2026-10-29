package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider

/**
 * SQL written by hand, with `@name` parameters, carrying the same terminal methods every builder carries.
 *
 * What separates it from the builders is not reach. Every clause they take is SQL and is passed through, so a
 * lateral join goes in `from`, a window function or `DISTINCT ON` in `select`, and `recursive()` is declared
 * on all four of them - there is not much they cannot say. What they do is the mechanical part: the keywords
 * and their order, the column list paired with its own placeholders, and the clauses that disappear when they
 * have nothing to say. This is for the statement you would rather write whole - one that already exists, one
 * that came from somewhere else, or one that assembling a clause at a time buys nothing.
 *
 * It is also the only query with an [execute], which is a different protocol rather than another terminal:
 * DDL, `SET` and several statements in one round trip live here because no builder can reach them.
 *
 * The SQL it was given comes back out of [toSql] like any other query's, rather than through a property of
 * its own.
 */
class RawQuery @PublishedApi internal constructor(
    provider: SessionProvider,
    private val sql: String
) : RunnableQuery<RawQuery>(provider) {

    override fun querySql(): String = sql

    /**
     * Runs the statement and discards whatever it produced - DDL, `SET`, administrative commands.
     *
     * It lives here rather than alongside the other terminals because it speaks the Simple Query Protocol,
     * and that protocol **binds nothing**: the SQL goes to the server exactly as written, so an `@name` left
     * in it arrives as literal text rather than as a parameter. A builder always has values to bind, which is
     * why none of them offers this - and why `INSERT`, `UPDATE` and `DELETE` written by hand belong in
     * [executeUpdate][RunnableQuery.executeUpdate] too, not here.
     *
     * What it does accept is several statements separated by `;` in one round trip, which PostgreSQL wraps in
     * an implicit transaction.
     *
     * @param ignoreRows Whether a statement returning rows may pass. Rows are discarded either way and there
     * is no reading them from here; what this decides is whether their arrival is reported. Left `false`,
     * `execute()` on a `SELECT` is caught rather than quietly doing nothing. Set it where the SQL is a script
     * somebody else wrote and a `SELECT` in it is legitimate — `pg_dump` emits `SELECT pg_catalog.setval(...)`
     * for every sequence.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `UNEXPECTED_RESULT` if
     * any statement in the SQL returned rows and [ignoreRows] is `false`.
     */
    fun execute(ignoreRows: Boolean = false) {
        queryProvider.execute { createNamedQuery(sql).execute(ignoreRows) }
    }
}
