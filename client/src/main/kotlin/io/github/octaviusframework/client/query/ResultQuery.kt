package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.DataResult
import io.github.octaviusframework.client.dbResult
import io.github.octaviusframework.driver.row.Row

/**
 * The same query, with every terminal handing back a [DataResult] instead of throwing.
 *
 * Reached by [RunnableQuery.asResult] and equivalent to wrapping the call in
 * [dbResult][io.github.octaviusframework.client.dbResult] - the same boundary, the same classification, the
 * same failures let through. What it changes is only the shape at the call site: a chain stays a chain rather
 * than being indented inside a lambda, which is worth something once a builder has put four calls in front of
 * the terminal.
 *
 * ```kotlin
 * val senators = db.rawQuery("SELECT id, cognomen FROM senate WHERE province_id = @p")
 *     .asResult()
 *     .fetchObjects<Senator>("p" to 7)
 * ```
 *
 * `dbResult { }` is still what to reach for around anything wider than one query - a whole unit of work, a
 * `db.execute { }` block, a `RawQuery.execute()`. Around a **transaction** reach for neither: use
 * [transactionResult][io.github.octaviusframework.client.OctaviusClient.transactionResult], which rolls back on a failure
 * rather than committing over one.
 */
class ResultQuery @PublishedApi internal constructor(
    @PublishedApi internal val query: RunnableQuery<*>
) {

    /** As [RunnableQuery.toSql] - the rendered SQL, for embedding in a larger statement or for a log line. */
    fun toSql(): String = query.toSql()

    // --- Rows -------------------------------------------------------------------------------------

    /** As [RunnableQuery.fetchRows]. */
    fun fetchRows(params: Map<String, Any?> = emptyMap()): DataResult<List<Row>> =
        dbResult { query.fetchRows(params) }

    /** As [RunnableQuery.fetchRows]. */
    fun fetchRows(vararg params: Pair<String, Any?>): DataResult<List<Row>> = fetchRows(params.toMap())

    /** As [RunnableQuery.fetchRow]. */
    fun fetchRow(params: Map<String, Any?> = emptyMap()): DataResult<Row?> =
        dbResult { query.fetchRow(params) }

    /** As [RunnableQuery.fetchRow]. */
    fun fetchRow(vararg params: Pair<String, Any?>): DataResult<Row?> = fetchRow(params.toMap())

    /** As [RunnableQuery.fetchRowStrict] - which means a missing row is still thrown, not returned. */
    fun fetchRowStrict(params: Map<String, Any?> = emptyMap()): DataResult<Row> =
        dbResult { query.fetchRowStrict(params) }

    /** As [RunnableQuery.fetchRowStrict] - which means a missing row is still thrown, not returned. */
    fun fetchRowStrict(vararg params: Pair<String, Any?>): DataResult<Row> = fetchRowStrict(params.toMap())

    /** As [RunnableQuery.forEachRow]. */
    fun forEachRow(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        block: (Row) -> Unit
    ): DataResult<Unit> = dbResult { query.forEachRow(params, fetchSize, block) }

    /** As [RunnableQuery.forEachRow]. */
    fun forEachRow(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        block: (Row) -> Unit
    ): DataResult<Unit> = forEachRow(params.toMap(), fetchSize, block)

    // --- Objects ----------------------------------------------------------------------------------

    /** As [RunnableQuery.fetchObjects]. */
    inline fun <reified T : Any> fetchObjects(params: Map<String, Any?> = emptyMap()): DataResult<List<T>> =
        dbResult { query.fetchObjects<T>(params) }

    /** As [RunnableQuery.fetchObjects]. */
    inline fun <reified T : Any> fetchObjects(vararg params: Pair<String, Any?>): DataResult<List<T>> =
        fetchObjects<T>(params.toMap())

    /** As [RunnableQuery.fetchObject]. */
    inline fun <reified T : Any> fetchObject(params: Map<String, Any?> = emptyMap()): DataResult<T?> =
        dbResult { query.fetchObject<T>(params) }

    /** As [RunnableQuery.fetchObject]. */
    inline fun <reified T : Any> fetchObject(vararg params: Pair<String, Any?>): DataResult<T?> =
        fetchObject<T>(params.toMap())

    /** As [RunnableQuery.fetchObjectStrict] - which means a missing row is still thrown, not returned. */
    inline fun <reified T : Any> fetchObjectStrict(params: Map<String, Any?> = emptyMap()): DataResult<T> =
        dbResult { query.fetchObjectStrict<T>(params) }

    /** As [RunnableQuery.fetchObjectStrict] - which means a missing row is still thrown, not returned. */
    inline fun <reified T : Any> fetchObjectStrict(vararg params: Pair<String, Any?>): DataResult<T> =
        fetchObjectStrict<T>(params.toMap())

    /** As [RunnableQuery.forEachObject]. */
    inline fun <reified T : Any> forEachObject(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        noinline block: (T) -> Unit
    ): DataResult<Unit> = dbResult { query.forEachObject<T>(params, fetchSize, block) }

    /** As [RunnableQuery.forEachObject]. */
    inline fun <reified T : Any> forEachObject(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        noinline block: (T) -> Unit
    ): DataResult<Unit> = forEachObject<T>(params.toMap(), fetchSize, block)

    // --- Fields -----------------------------------------------------------------------------------

    /** As [RunnableQuery.fetchFields]. */
    inline fun <reified T> fetchFields(params: Map<String, Any?> = emptyMap()): DataResult<List<T>> =
        dbResult { query.fetchFields<T>(params) }

    /** As [RunnableQuery.fetchFields]. */
    inline fun <reified T> fetchFields(vararg params: Pair<String, Any?>): DataResult<List<T>> =
        fetchFields<T>(params.toMap())

    /** As [RunnableQuery.fetchField] - a non-nullable [T] over a missing row is still thrown. */
    inline fun <reified T> fetchField(params: Map<String, Any?> = emptyMap()): DataResult<T> =
        dbResult { query.fetchField<T>(params) }

    /** As [RunnableQuery.fetchField] - a non-nullable [T] over a missing row is still thrown. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): DataResult<T> =
        fetchField<T>(params.toMap())

    /** As [RunnableQuery.fetchFieldStrict]. */
    inline fun <reified T> fetchFieldStrict(params: Map<String, Any?> = emptyMap()): DataResult<T> =
        dbResult { query.fetchFieldStrict<T>(params) }

    /** As [RunnableQuery.fetchFieldStrict]. */
    inline fun <reified T> fetchFieldStrict(vararg params: Pair<String, Any?>): DataResult<T> =
        fetchFieldStrict<T>(params.toMap())

    /** As [RunnableQuery.forEachField]. */
    inline fun <reified T> forEachField(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        noinline block: (T) -> Unit
    ): DataResult<Unit> = dbResult { query.forEachField<T>(params, fetchSize, block) }

    /** As [RunnableQuery.forEachField]. */
    inline fun <reified T> forEachField(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        noinline block: (T) -> Unit
    ): DataResult<Unit> = forEachField<T>(params.toMap(), fetchSize, block)

    // --- Modification -----------------------------------------------------------------------------

    /** As [RunnableQuery.update]. */
    fun update(params: Map<String, Any?> = emptyMap()): DataResult<Long> =
        dbResult { query.update(params) }

    /** As [RunnableQuery.update]. */
    fun update(vararg params: Pair<String, Any?>): DataResult<Long> = update(params.toMap())
}
