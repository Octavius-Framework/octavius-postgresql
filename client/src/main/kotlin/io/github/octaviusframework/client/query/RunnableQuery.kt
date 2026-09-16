package io.github.octaviusframework.client.query

import io.github.octaviusframework.client.session.SessionProvider
import io.github.octaviusframework.client.transaction.StepBuilder
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.query.NamedParameterQuery
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * Something that knows how to produce a query, and can therefore be run.
 *
 * This is where the terminal methods live, written once for everything that can be run. Every builder the
 * client offers extends it, and so does a hand-written [RawQuery]; a subclass supplies the SQL and inherits
 * `fetchRows`, `fetchObjects`, `fetchField`, `forEach*` and `update` without restating a line of them.
 *
 * The names and their meanings are the driver's, unchanged, and parameters are supplied at the terminal as
 * they are there. What these add is the one thing a `RunnableQuery` knows and a driver query does not: which
 * session to run on. Nothing has to be opened around a query, which is what keeps a single one to a single
 * expression - the session is asked for when a terminal runs, and inside a transaction that is the
 * transaction's own.
 *
 * They throw, as the driver throws. Where a failure should be a value instead, [asResult] switches this query
 * to terminals that return one.
 *
 * Parameters are `@name` only. Positional `$1` placeholders stay reachable where they always were -
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

    // Null until something is registered, a query being a thing an application builds per request: an empty
    // list apiece would be two allocations on every one of them for a feature most never use.
    @PublishedApi
    internal var queryResultConverters: MutableList<ResultConverter<*, *>>? = null

    @PublishedApi
    internal var queryParameterConverters: MutableList<ParameterConverter<*>>? = null

    /**
     * Builds the driver query a terminal is about to run, with this query's own converters on it.
     *
     * Every terminal goes through here rather than calling `createNamedQuery` itself, which is what makes
     * [registerResultConverter] apply to all of them at once.
     */
    @PublishedApi
    internal fun OctaviusSessionOperations.preparedQuery(): NamedParameterQuery {
        val query = createNamedQuery(querySql())
        queryResultConverters?.forEach { query.registerResultConverter(it) }
        queryParameterConverters?.forEach { query.registerParameterConverter(it) }
        return query
    }

    /**
     * Registers a [ResultConverter] for this query and nothing else.
     *
     * The driver lets every query hold converters of its own, consulted ahead of the registered ones and thrown
     * away with the query; this is how a builder reaches them. A one-off mapping - a column read as something other
     * than what the registry says, a shape that exists in one report and nowhere else - therefore costs nothing
     * outside the query it was written for, where registering on the type manager would reach every session
     * pointing at the same database.
     *
     * Registered ahead of whatever the session already holds, and later registrations here win over earlier
     * ones.
     *
     * ```kotlin
     * db.select("payload").from("dispatches")
     *     .registerResultConverter(SealedEnvelopeConverter)
     *     .where("legion_id = @id")
     *     .fetchObjects<Envelope>("id" to 7)
     * ```
     *
     * @param converter The converter to consult for this query.
     * @return This query, as its own type, so it can sit anywhere in a builder chain.
     */
    fun registerResultConverter(converter: ResultConverter<*, *>): T {
        (queryResultConverters ?: mutableListOf<ResultConverter<*, *>>().also { queryResultConverters = it })
            .add(converter)
        return this as T
    }

    /**
     * Registers a [ParameterConverter] for this query and nothing else.
     *
     * The write-side mirror of [registerResultConverter], on the same terms: local to the query, ahead of the
     * session's, and discarded with it.
     *
     * @param converter The converter to consult for this query.
     * @return This query, as its own type.
     */
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
     * Renders the SQL this query would send.
     *
     * A query here is a value rather than an action waiting to happen, and this is what makes it composable:
     * the rendered SQL drops into a `WITH` clause, a subquery, an arm of a `UNION` - anywhere the statement it
     * produces belongs inside a larger one. Parameters are never carried by a query and are always supplied at
     * the terminal, so the `@name` placeholders survive the embedding untouched and are bound by whoever runs
     * the outer statement. The plainer uses follow from the same method: a log line, a test that asserts on
     * what a builder generated.
     *
     * Rendering is not cached and costs whatever assembling costs, which for a builder means walking its
     * clauses again. Composing a statement once per request is nothing; doing it per row is not.
     */
    fun toSql(): String = querySql()

    /**
     * Switches this query to the result style: every terminal on the returned object hands back a
     * [DataResult][io.github.octaviusframework.client.DataResult] instead of throwing.
     *
     * The boundary is the same one [dbResult][io.github.octaviusframework.client.dbResult] applies, so what is
     * caught and what is let through does not change - a `fetch*Strict` that found no row still throws. Only
     * the shape at the call site does, and it is worth the switch once a builder has put four calls in front
     * of the terminal and wrapping the chain would mean indenting all of it.
     *
     * @return This query, with result-returning terminals.
     */
    fun asResult(): ResultQuery = ResultQuery(this)

    /**
     * Turns this query into a step of a [TransactionPlan][io.github.octaviusframework.client.transaction.TransactionPlan]
     * instead of something to run now.
     *
     * The terminal chosen on the returned builder decides what the step produces and how later steps can refer
     * to it; the parameters given there may hold
     * [TransactionValue][io.github.octaviusframework.client.transaction.TransactionValue]s pointing at earlier
     * steps.
     *
     * @return A builder for the step.
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
     * Runs the query and hands each row to [block] as it arrives, in batches of [fetchSize].
     *
     * The session stays open for the whole walk, so this streams a result too large to hold rather than
     * materialising it first. [block] runs on that session, and the driver serialises nested use of one
     * connection, so keep the body to the row.
     *
     * @param fetchSize Rows per batch, or `0` for the whole result in one `Execute`. Required, as it is on the
     * driver.
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

    /**
     * Runs the query and hands each row, mapped onto [T], to [block] as it arrives.
     *
     * @param fetchSize As on [forEachRow]: rows per batch, `0` for the whole result at once, and required.
     */
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
     * Runs the query and returns the first column of its single row as [T].
     *
     * Declare [T] nullable for a lookup allowed to find nothing; under a non-nullable one, a missing row and a
     * `NULL` alike are thrown - the declaration asserted a value would be there.
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

    /**
     * Runs the query and hands the first column of each row, as [T], to [block] as it arrives.
     *
     * @param fetchSize As on [forEachRow]: rows per batch, `0` for the whole result at once, and required.
     */
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
    fun update(params: Map<String, Any?> = emptyMap()): Long =
        queryProvider.execute { preparedQuery().update(params) }

    /** Runs the statement and returns how many rows it affected. */
    fun update(vararg params: Pair<String, Any?>): Long = update(params.toMap())
}
