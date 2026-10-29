package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.row.Row

/**
 * One operation of a [TransactionPlan], described but not yet run.
 *
 * Built by [StepBuilder], which is what [RunnableQuery.asStep] hands back. It carries the query, the parameters as
 * they were written - unresolved [TransactionValue]s and all - and the terminal to call once they are
 * resolved. The query is kept alongside the terminal so that a plan can render its SQL before running any of
 * it; nothing else reads it.
 *
 * @param T What running it will produce.
 */
class TransactionStep<T> @PublishedApi internal constructor(
    @PublishedApi internal val query: RunnableQuery<*>,
    @PublishedApi internal val params: Map<String, Any?>,
    @PublishedApi internal val run: (Map<String, Any?>) -> T
)

/**
 * Turns a query into a step instead of running it.
 *
 * The terminals here are the ones that make sense for something whose result is stored and referred to later:
 * the `fetch*` family and `executeUpdate`. The `forEach*` family is absent on purpose - a plan keeps every result so
 * that later steps can use it, and a walk over rows too large to hold has nothing to keep. So is
 * [RawQuery.execute][io.github.octaviusframework.client.query.RawQuery.execute], reachable though it is on a
 * raw query: it speaks a protocol that binds nothing, so a step made of one could take no
 * [TransactionValue] and could therefore refer to no earlier step, which is the only thing a step does that a
 * lambda does not. DDL and `SET` belong in a query run on the transaction's own session; the timeouts worth
 * setting are properties of [TransactionDefinition] already.
 *
 * Parameters may hold [TransactionValue]s among ordinary values, and only those are resolved. A
 * [SpreadParameters] goes among them too, and fills its slot with a row's worth of parameters rather
 * than with one.
 *
 * ```kotlin
 * val edictId = plan.add(
 *     db.insertInto("edicts").values(edict).returning("id")
 *         .asStep().fetchFieldStrict<Int>(edict)
 * )
 *
 * for (item in levy) {
 *     plan.add(
 *         db.insertInto("edict_items").values(listOf("edict_id", "province_id", "amount"))
 *             .asStep().executeUpdate(
 *                 "edict_id" to edictId.value(),
 *                 "province_id" to item.provinceId,
 *                 "amount" to item.amount
 *             )
 *     )
 * }
 * ```
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
