package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.row.Row

/**
 * Turns a query into a step of a [TransactionPlan] instead of running it; returned by
 * [asStep][TransactionPlan.asStep]. Each terminal adds the step to the plan and returns its [StepHandle].
 *
 * It has the `fetch*` terminals and `executeUpdate`. There is no `forEach*`, a plan keeping every result, and no
 * [RawQuery.execute][io.github.octaviusframework.client.query.RawQuery.execute], which binds nothing and so could
 * take no [TransactionValue]. Parameters may hold [TransactionValue]s and [SpreadParameters] among ordinary
 * values.
 */
class StepBuilder internal constructor(
    @PublishedApi internal val plan: TransactionPlan,
    @PublishedApi internal val query: RunnableQuery<*>
) {

    /** Adds a step that returns every row. */
    fun fetchRows(params: Map<String, Any?> = emptyMap()): StepHandle<List<Row>> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchRows(p) }

    /** Adds a step that returns every row. */
    fun fetchRows(vararg params: Pair<String, Any?>): StepHandle<List<Row>> = fetchRows(params.toMap())

    /** Adds a step that returns one row, or `null` where none matched. */
    fun fetchRow(params: Map<String, Any?> = emptyMap()): StepHandle<Row?> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchRow(p) }

    /** Adds a step that returns one row, or `null` where none matched. */
    fun fetchRow(vararg params: Pair<String, Any?>): StepHandle<Row?> = fetchRow(params.toMap())

    /** Adds a step that returns one row and fails where the count was anything but one. */
    fun fetchRowStrict(params: Map<String, Any?> = emptyMap()): StepHandle<Row> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchRowStrict(p) }

    /** Adds a step that returns one row and fails where the count was anything but one. */
    fun fetchRowStrict(vararg params: Pair<String, Any?>): StepHandle<Row> = fetchRowStrict(params.toMap())

    /** Adds a step that maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(params: Map<String, Any?> = emptyMap()): StepHandle<List<T>> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchObjects<T>(p) }

    /** Adds a step that maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(vararg params: Pair<String, Any?>): StepHandle<List<T>> =
        fetchObjects<T>(params.toMap())

    /** Adds a step that maps one row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(params: Map<String, Any?> = emptyMap()): StepHandle<T?> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchObject<T>(p) }

    /** Adds a step that maps one row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(vararg params: Pair<String, Any?>): StepHandle<T?> =
        fetchObject<T>(params.toMap())

    /** Adds a step that maps one row onto [T] and fails where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(params: Map<String, Any?> = emptyMap()): StepHandle<T> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchObjectStrict<T>(p) }

    /** Adds a step that maps one row onto [T] and fails where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(vararg params: Pair<String, Any?>): StepHandle<T> =
        fetchObjectStrict<T>(params.toMap())

    /** Adds a step that returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(params: Map<String, Any?> = emptyMap()): StepHandle<List<T>> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchFields<T>(p) }

    /** Adds a step that returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(vararg params: Pair<String, Any?>): StepHandle<List<T>> =
        fetchFields<T>(params.toMap())

    /** Adds a step that returns the first column of one row as [T]. */
    inline fun <reified T> fetchField(params: Map<String, Any?> = emptyMap()): StepHandle<T> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchField<T>(p) }

    /** Adds a step that returns the first column of one row as [T]. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): StepHandle<T> =
        fetchField<T>(params.toMap())

    /** As [fetchField], but the step's query must return exactly one row. */
    inline fun <reified T> fetchFieldStrict(params: Map<String, Any?> = emptyMap()): StepHandle<T> =
        plan.addStep(query, params) { p -> query.preparedQuery().fetchFieldStrict<T>(p) }

    /** As [fetchField], but the step's query must return exactly one row. */
    inline fun <reified T> fetchFieldStrict(vararg params: Pair<String, Any?>): StepHandle<T> =
        fetchFieldStrict<T>(params.toMap())

    /** Adds a step that runs the statement and returns how many rows it affected. */
    fun executeUpdate(params: Map<String, Any?> = emptyMap()): StepHandle<Long> =
        plan.addStep(query, params) { p -> query.preparedQuery().executeUpdate(p) }

    /** Adds a step that runs the statement and returns how many rows it affected. */
    fun executeUpdate(vararg params: Pair<String, Any?>): StepHandle<Long> = executeUpdate(params.toMap())
}
