package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider
import io.github.octaviusframework.client.transaction.StepBuilder
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.query.NamedParameterQuery
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * A query that can be run: the base of every builder and of [RawQuery], carrying the terminal methods.
 *
 * The terminals are the driver's, with the same names and meanings, and find their session when they run -
 * inside a transaction, the transaction's. They throw as the driver throws; [asResult] switches to terminals
 * that return a value.
 *
 * Parameters are `@name` only and are supplied at the terminal. Positional `$1` placeholders are reached through
 * `db.execute { createNativeQuery(…) }`.
 */
@Suppress("UNCHECKED_CAST")
abstract class RunnableQuery<T : RunnableQuery<T>> @PublishedApi internal constructor(
    /** Decides which session the terminals run on. */
    @PublishedApi internal val queryProvider: SessionProvider
) {

    /** Renders the SQL. Called once per terminal call, so a builder may put off assembling it until here. */
    @PublishedApi
    internal abstract fun querySql(): String

    // Null until something is registered, so a query that registers nothing allocates no lists.
    @PublishedApi
    internal var queryResultConverters: MutableList<ResultConverter<*, *>>? = null

    @PublishedApi
    internal var queryParameterConverters: MutableList<ParameterConverter<*>>? = null

    /** The driver query every terminal runs, carrying this query's own converters. */
    @PublishedApi
    internal fun OctaviusSessionOperations.preparedQuery(): NamedParameterQuery {
        val query = createNamedQuery(querySql())
        queryResultConverters?.forEach { query.registerResultConverter(it) }
        queryParameterConverters?.forEach { query.registerParameterConverter(it) }
        return query
    }

    /**
     * Registers a [ResultConverter] for this query alone, consulted ahead of the session's. A later registration
     * here wins over an earlier one.
     *
     * ```kotlin
     * db.select("payload").from("dispatches")
     *     .registerResultConverter(SealedEnvelopeConverter)
     *     .where("legion_id = @id")
     *     .fetchObjects<Envelope>("id" to 7)
     * ```
     */
    fun registerResultConverter(converter: ResultConverter<*, *>): T {
        (queryResultConverters ?: mutableListOf<ResultConverter<*, *>>().also { queryResultConverters = it })
            .add(converter)
        return this as T
    }

    /** Registers a [ParameterConverter] for this query alone, on the terms of [registerResultConverter]. */
    fun registerParameterConverter(converter: ParameterConverter<*>): T {
        (queryParameterConverters ?: mutableListOf<ParameterConverter<*>>().also { queryParameterConverters = it })
            .add(converter)
        return this as T
    }

    /** Carries registered converters into a builder's `copy()`, alongside whatever clauses it copies. */
    internal fun copyConvertersFrom(other: RunnableQuery<*>) {
        other.queryResultConverters?.let { queryResultConverters = it.toMutableList() }
        other.queryParameterConverters?.let { queryParameterConverters = it.toMutableList() }
    }

    /**
     * Renders the SQL this query would send. A query never carries parameters, so its `@name` placeholders survive
     * embedding it in a `WITH`, a subquery or a `UNION`, and are bound by whoever runs the outer statement. Not
     * cached: a builder walks its clauses again on every call.
     */
    fun toSql(): String = querySql()

    /**
     * This query with terminals that return a [DataResult][io.github.octaviusframework.client.DataResult] instead
     * of throwing, on the boundary [dbResult][io.github.octaviusframework.client.dbResult] applies - a
     * `fetch*Strict` that found no row still throws.
     */
    fun asResult(): ResultQuery = ResultQuery(this)

    /**
     * Turns this query into a step of a
     * [TransactionPlan][io.github.octaviusframework.client.transaction.TransactionPlan] instead of running it. The
     * parameters given at the step's terminal may hold
     * [TransactionValue][io.github.octaviusframework.client.transaction.TransactionValue]s pointing at earlier
     * steps.
     */
    fun asStep(): StepBuilder = StepBuilder(this)

    // --- Rows -------------------------------------------------------------------------------------

    /** Runs the query and returns every row. */
    fun fetchRows(params: Map<String, Any?> = emptyMap()): List<Row> =
        queryProvider.execute { preparedQuery().fetchRows(params) }

    /** Runs the query and returns every row. */
    fun fetchRows(vararg params: Pair<String, Any?>): List<Row> = fetchRows(params.toMap())

    /** Runs the query and returns its single row, or `null` where none matched. */
    fun fetchRow(params: Map<String, Any?> = emptyMap()): Row? =
        queryProvider.execute { preparedQuery().fetchRow(params) }

    /** Runs the query and returns its single row, or `null` where none matched. */
    fun fetchRow(vararg params: Pair<String, Any?>): Row? = fetchRow(params.toMap())

    /** Runs the query and returns its single row, throwing where the count was anything but one. */
    fun fetchRowStrict(params: Map<String, Any?> = emptyMap()): Row =
        queryProvider.execute { preparedQuery().fetchRowStrict(params) }

    /** Runs the query and returns its single row, throwing where the count was anything but one. */
    fun fetchRowStrict(vararg params: Pair<String, Any?>): Row = fetchRowStrict(params.toMap())

    /**
     * Runs the query and hands each row to [block] as it arrives, so a result too large to hold is streamed.
     * [block] runs while the result is still being read, and a query from inside it is refused with
     * `CONNECTION_BUSY`.
     *
     * @param fetchSize Rows per batch, or `0` for the whole result in one `Execute`.
     */
    fun forEachRow(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        block: (Row) -> Unit
    ) = queryProvider.execute { preparedQuery().forEachRow(params, fetchSize, block) }


    /** Runs the query and hands each row to [block] as it arrives, in batches of [fetchSize]. */
    fun forEachRow(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        block: (Row) -> Unit
    ) = forEachRow(params.toMap(), fetchSize, block)

    // --- Objects ----------------------------------------------------------------------------------

    /** Runs the query and maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(params: Map<String, Any?> = emptyMap()): List<T> =
        queryProvider.execute { preparedQuery().fetchObjects<T>(params) }

    /** Runs the query and maps every row onto [T]. */
    inline fun <reified T : Any> fetchObjects(vararg params: Pair<String, Any?>): List<T> =
        fetchObjects<T>(params.toMap())

    /** Runs the query and maps its single row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(params: Map<String, Any?> = emptyMap()): T? =
        queryProvider.execute { preparedQuery().fetchObject<T>(params) }

    /** Runs the query and maps its single row onto [T], or `null` where none matched. */
    inline fun <reified T : Any> fetchObject(vararg params: Pair<String, Any?>): T? =
        fetchObject<T>(params.toMap())

    /** Runs the query and maps its single row onto [T], throwing where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(params: Map<String, Any?> = emptyMap()): T =
        queryProvider.execute { preparedQuery().fetchObjectStrict<T>(params) }

    /** Runs the query and maps its single row onto [T], throwing where the count was anything but one. */
    inline fun <reified T : Any> fetchObjectStrict(vararg params: Pair<String, Any?>): T =
        fetchObjectStrict<T>(params.toMap())

    /** Runs the query and hands each row, mapped onto [T], to [block] as it arrives, as [forEachRow] does. */
    inline fun <reified T : Any> forEachObject(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        noinline block: (T) -> Unit
    ) {
        queryProvider.execute { preparedQuery().forEachObject<T>(params, fetchSize, block) }
    }

    /** Runs the query and hands each row, mapped onto [T], to [block] as it arrives. */
    inline fun <reified T : Any> forEachObject(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        noinline block: (T) -> Unit
    ) = forEachObject<T>(params.toMap(), fetchSize, block)

    // --- Fields -----------------------------------------------------------------------------------

    /** Runs the query and returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(params: Map<String, Any?> = emptyMap()): List<T> =
        queryProvider.execute { preparedQuery().fetchFields<T>(params) }

    /** Runs the query and returns the first column of every row as [T]. */
    inline fun <reified T> fetchFields(vararg params: Pair<String, Any?>): List<T> =
        fetchFields<T>(params.toMap())

    /**
     * Runs the query and returns the first column of its single row as [T]. A nullable [T] gives `null` for a
     * missing row or a `NULL`; a non-nullable one throws for either.
     */
    inline fun <reified T> fetchField(params: Map<String, Any?> = emptyMap()): T =
        queryProvider.execute { preparedQuery().fetchField<T>(params) }

    /** Runs the query and returns the first column of its single row as [T]. */
    inline fun <reified T> fetchField(vararg params: Pair<String, Any?>): T =
        fetchField<T>(params.toMap())

    /** As [fetchField], but the query must return exactly one row rather than at most one. */
    inline fun <reified T> fetchFieldStrict(params: Map<String, Any?> = emptyMap()): T =
        queryProvider.execute { preparedQuery().fetchFieldStrict<T>(params) }

    /** As [fetchField], but the query must return exactly one row rather than at most one. */
    inline fun <reified T> fetchFieldStrict(vararg params: Pair<String, Any?>): T =
        fetchFieldStrict<T>(params.toMap())

    /** Runs the query and hands the first column of each row, as [T], to [block] as [forEachRow] does. */
    inline fun <reified T> forEachField(
        params: Map<String, Any?> = emptyMap(),
        fetchSize: Int,
        noinline block: (T) -> Unit
    ) = queryProvider.execute { preparedQuery().forEachField<T>(params, fetchSize, block) }


    /** Runs the query and hands the first column of each row, as [T], to [block] as it arrives. */
    inline fun <reified T> forEachField(
        vararg params: Pair<String, Any?>,
        fetchSize: Int,
        noinline block: (T) -> Unit
    ) = forEachField<T>(params.toMap(), fetchSize, block)

    // --- Modification -----------------------------------------------------------------------------

    /** Runs the statement and returns how many rows it affected. */
    fun executeUpdate(params: Map<String, Any?> = emptyMap()): Long =
        queryProvider.execute { preparedQuery().executeUpdate(params) }

    /** Runs the statement and returns how many rows it affected. */
    fun executeUpdate(vararg params: Pair<String, Any?>): Long = executeUpdate(params.toMap())
}
