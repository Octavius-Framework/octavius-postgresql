package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.row.Row

/**
 * One operation of a [TransactionPlan], built by [StepBuilder] but not yet run: the query, its parameters as
 * written, and the terminal to call once they are resolved.
 *
 * @param T What running it will produce.
 */
class TransactionStep<T> @PublishedApi internal constructor(
    @PublishedApi internal val query: RunnableQuery<*>,
    @PublishedApi internal val params: Map<String, Any?>,
    @PublishedApi internal val run: (Map<String, Any?>) -> T
)

/**
 * Turns a query into a [TransactionPlan] step instead of running it; returned by [RunnableQuery.asStep].
 *
 * It has the `fetch*` terminals and `executeUpdate`. There is no `forEach*`, a plan keeping every result, and no
 * [RawQuery.execute][io.github.octaviusframework.client.query.RawQuery.execute], which binds nothing and so could
 * take no [TransactionValue]. Parameters may hold [TransactionValue]s and [SpreadParameters] among ordinary
 * values.
 */
class StepBuilder @PublishedApi internal constructor(
    @PublishedApi internal val query: RunnableQuery<*>
) {

    /** A step that returns every row. */
    fun fetchRows(params: Map<String, Any?> = emptyMap()): TransactionStep<List<Row>> =
        TransactionStep(query, params) { query.fetchRows(it) }

    /** A step that returns every row. */
    fun fetchRows(vararg params: Pair<String, Any?>): TransactionStep<List<Row>> = fetchRows(params.toMap())

    /** A step that returns one row, or `null` where none matched. */
    fun fetchRow(params: Map<String, Any?> = emptyMap()): TransactionStep<Row?> =
        TransactionStep(query, params) { query.fetchRow(it) }

    /** A step that returns one row, or `null` where none matched. */
    fun fetchRow(vararg params: Pair<String, Any?>): TransactionStep<Row?> = fetchRow(params.toMap())

    /** A step that returns one row and fails where the count was anything but one. */
    fun fetchRowStrict(params: Map<String, Any?> = emptyMap()): TransactionStep<Row> =
        TransactionStep(query, params) { query.fetchRowStrict(it) }

    /** A step that returns one row and fails where the count was anything but one. */
    fun fetchRowStrict(vararg params: Pair<String, Any?>): TransactionStep<Row> = fetchRowStrict(params.toMap())

    /** A step that maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(params: Map<String, Any?> = emptyMap()): TransactionStep<List<T>> =
        TransactionStep(query, params) { query.fetchObjects<T>(it) }

    /** A step that maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(vararg params: Pair<String, Any?>): TransactionStep<List<T>> =
        fetchObjects<T>(params.toMap())

    /** A step that maps one row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(params: Map<String, Any?> = emptyMap()): TransactionStep<T?> =
        TransactionStep(query, params) { query.fetchObject<T>(it) }

    /** A step that maps one row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(vararg params: Pair<String, Any?>): TransactionStep<T?> =
        fetchObject<T>(params.toMap())

    /** A step that maps one row onto [T] and fails where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(params: Map<String, Any?> = emptyMap()): TransactionStep<T> =
        TransactionStep(query, params) { query.fetchObjectStrict<T>(it) }

    /** A step that maps one row onto [T] and fails where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(vararg params: Pair<String, Any?>): TransactionStep<T> =
        fetchObjectStrict<T>(params.toMap())

    /** A step that returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(params: Map<String, Any?> = emptyMap()): TransactionStep<List<T>> =
        TransactionStep(query, params) { query.fetchFields<T>(it) }

    /** A step that returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(vararg params: Pair<String, Any?>): TransactionStep<List<T>> =
        fetchFields<T>(params.toMap())

    /** A step that returns the first column of one row as [T]. */
    inline fun <reified T> fetchField(params: Map<String, Any?> = emptyMap()): TransactionStep<T> =
        TransactionStep(query, params) { query.fetchField<T>(it) }

    /** A step that returns the first column of one row as [T]. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): TransactionStep<T> =
        fetchField<T>(params.toMap())

    /** As [fetchField], but the step's query must return exactly one row. */
    inline fun <reified T> fetchFieldStrict(params: Map<String, Any?> = emptyMap()): TransactionStep<T> =
        TransactionStep(query, params) { query.fetchFieldStrict<T>(it) }

    /** As [fetchField], but the step's query must return exactly one row. */
    inline fun <reified T> fetchFieldStrict(vararg params: Pair<String, Any?>): TransactionStep<T> =
        fetchFieldStrict<T>(params.toMap())

    /** A step that runs the statement and returns how many rows it affected. */
    fun executeUpdate(params: Map<String, Any?> = emptyMap()): TransactionStep<Long> =
        TransactionStep(query, params) { query.executeUpdate(it) }

    /** A step that runs the statement and returns how many rows it affected. */
    fun executeUpdate(vararg params: Pair<String, Any?>): TransactionStep<Long> = executeUpdate(params.toMap())
}
