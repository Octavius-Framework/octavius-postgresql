/*
 *                      ____   _____ _______  __      _______ _    _  _____
 *                     / __ \ / ____|__   __|/\ \    / /_   _| |  | |/ ____|
 *                    | |  | | |       | |  /  \ \  / /  | | | |  | | (___
 *                    | |  | | |       | | / /\ \ \/ /   | | | |  | |\___ \
 *                    | |__| | |____   | |/ ____ \  /   _| |_| |__| |____) |
 *                     \____/ \_____|  |_/_/    \_\/   |_____|\____/|_____/
 *                   --------------------------------------------------------
 *                                        OCTAVIUS CLIENT
 *                   --------------------------------------------------------
 */
package io.github.octaviusframework.client

import io.github.octaviusframework.client.dynamic.DynamicTypes
import io.github.octaviusframework.client.dynamic.DynamicWriteStrategy
import io.github.octaviusframework.client.query.DeleteQuery
import io.github.octaviusframework.client.query.InsertQuery
import io.github.octaviusframework.client.query.RawQuery
import io.github.octaviusframework.client.query.SelectQuery
import io.github.octaviusframework.client.query.UpdateQuery
import io.github.octaviusframework.client.session.DefaultSessionProvider
import io.github.octaviusframework.client.session.SessionProvider
import io.github.octaviusframework.client.transaction.StepHandle
import io.github.octaviusframework.client.transaction.TransactionPlan
import io.github.octaviusframework.client.transaction.TransactionPlanResult
import io.github.octaviusframework.client.transaction.TransactionPropagation
import io.github.octaviusframework.client.transaction.resolveParams
import io.github.octaviusframework.client.transaction.validatePlan
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.octaviusframework.serializer.octaviusJson
import kotlinx.serialization.json.Json
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * Hands out queries and sessions, and runs transactions over them. That is all it is.
 *
 * A query taken from here - [rawQuery], [select], [insertInto], [update], [deleteFrom] - is a
 * [RunnableQuery][io.github.octaviusframework.client.query.RunnableQuery], which carries the terminal family
 * and finds its own session when one of them is called. Nothing has to be opened around it, which is what
 * keeps a single query to a single expression.
 *
 * Where the work is not a query - `copy`, `largeObjects`, `notifications`, or several statements that have to
 * share one session - [execute] hands over the driver's own [OctaviusSessionOperations] and gets out of the
 * way. Neither path wraps or renames anything the driver named.
 *
 * ```kotlin
 * val db = OctaviusClient.fromDataSource(hikariDataSource)
 *
 * val senators = db.rawQuery("SELECT id, cognomen FROM senators WHERE province_id = @p")
 *     .fetchObjects<Senator>("p" to 7)
 * ```
 *
 * What it buys over calling `dataSource.getOctaviusSession()` by hand is the thing a data layer of any size
 * ends up needing: a repository function can open a scope without knowing whether it is already inside a
 * transaction, and be right either way. A session opened here joins the transaction running on this thread
 * if there is one, so the same function works standalone and as a step in a larger unit of work, without a
 * session in its signature.
 *
 * Failures arrive as exceptions, which is what the driver raises and what a `try`/`catch` expects. Where a
 * failure should be a value instead, the result style has a door for each width:
 * [asResult][io.github.octaviusframework.client.query.RunnableQuery.asResult] for one query,
 * [transactionResult] for a transaction, and [dbResult] for anything else.
 */
interface OctaviusClient : AutoCloseable {

    /**
     * Runs [block] on a session, and gives that session back when it returns.
     *
     * Inside a [transaction] on this thread the session is the transaction's and the work joins it, committing
     * or rolling back with it. Outside one, the session is borrowed for this call alone and its statements
     * commit as they go, exactly as auto-commit statements do.
     *
     * @param block The work to run against the session.
     * @return Whatever [block] produced.
     */
    fun <T> execute(block: OctaviusSessionOperations.() -> T): T

    /**
     * The `dynamic_dto` types this client knows: where they are registered, and where a value is wrapped for
     * writing.
     *
     * Untouched until something registers a type, so an application that does not use them pays nothing and
     * the database is never asked for a type it does not have.
     */
    val dynamicTypes: DynamicTypes

    /**
     * Prepares SQL written by hand, with `@name` parameters.
     *
     * Nothing is sent until one of the query's terminal methods is called, and calling two of them runs it
     * twice.
     *
     * @param sql The query, with parameters written as `@name`.
     * @return The query, ready to be run.
     */
    fun rawQuery(sql: String): RawQuery

    /**
     * Starts building a `SELECT` over the given columns.
     *
     * Each column is SQL and is passed through: `"id"`, `"count(*) AS total"`, `"p.name AS province_name"`.
     *
     * @param columns What to select. At least one.
     * @return The builder.
     */
    fun select(vararg columns: String): SelectQuery

    /**
     * Starts building an `INSERT` into the given table.
     *
     * @param table The table to insert into.
     * @return The builder.
     */
    fun insertInto(table: String): InsertQuery

    /**
     * Starts building an `UPDATE` of the given table. A `WHERE` is required before it will render.
     *
     * @param table The table to update.
     * @return The builder.
     */
    fun update(table: String): UpdateQuery

    /**
     * Starts building a `DELETE` from the given table. A `WHERE` is required before it will render.
     *
     * @param table The table to delete from.
     * @return The builder.
     */
    fun deleteFrom(table: String): DeleteQuery

    /**
     * Runs [block] inside a transaction, committing when it returns and rolling back when it throws.
     *
     * Every [execute] made from this thread while [block] runs lands on the transaction's session, so a
     * repository function called from in here joins the transaction without being told about it. That binding
     * is per-thread and does not follow work handed to another one: a coroutine launched on a different
     * dispatcher inside [block] gets a session and a transaction of its own, and the commit here says nothing
     * about it.
     *
     * The receiver is this client, so queries inside read exactly as they do outside. What it does not offer
     * is a way to commit or roll back: the surrounding block is what decides, and a session reached through
     * [execute] is an [OctaviusSessionOperations] rather than the full session for the same reason.
     *
     * @param propagation What to do about a transaction already running on this thread.
     * @param isolation The isolation level to run at, or `null` for the server's. Ignored when joining a
     * transaction that is already running, which cannot change the level it began at.
     * @param readOnly Whether the transaction refuses writes. Ignored on the same terms as [isolation].
     * @param statementTimeout Aborts any single statement running longer than this.
     * @param transactionTimeout Aborts the transaction once it has been open longer than this.
     * @param block The work to run in the transaction.
     * @return Whatever [block] produced.
     */
    fun <T> transaction(
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        isolation: TransactionIsolationLevel? = null,
        readOnly: Boolean = false,
        statementTimeout: Duration? = null,
        transactionTimeout: Duration? = null,
        block: OctaviusClient.() -> T
    ): T

    /**
     * Runs [block] in a transaction that understands a returned failure, and hands back what it produced.
     *
     * This is [transaction] for the result style, and it exists because the two do not compose by themselves.
     * A plain transaction rolls back on a throw and on nothing else, so a `dbResult` inside one turns the
     * failure into a value, the block finishes normally, and the transaction **goes on to commit** - the same
     * trap `runCatching` sets in the same place. Over a failure the block made itself that commit goes through;
     * over one the server raised the driver refuses it, and the failure arrives as that refusal, at the end of
     * the block. Here a returned [DataResult.Failure] rolls back, and comes out as the value it already was.
     *
     * ```kotlin
     * val created = db.transactionResult {
     *     val id = rawQuery("INSERT INTO citizens (name) VALUES (@n) RETURNING id")
     *         .asResult().fetchFieldStrict<Int>("n" to name)
     *         .getOrElse { return@transactionResult it }
     *
     *     rawQuery("INSERT INTO citizen_profiles (citizen_id, bio) VALUES (@id, @bio)")
     *         .asResult().update("id" to id, "bio" to bio)
     *         .map { id }
     * }
     * ```
     *
     * Three ways out, and they are not the same:
     *
     * - [DataResult.Success]. The transaction commits and that result is returned.
     * - [DataResult.Failure]. The transaction **rolls back** and that same failure is returned. A failure that
     *   reached the return value is one the block chose not to handle, so it takes the transaction with it.
     * - A throw. The transaction rolls back. A database failure the boundary counts as recoverable comes back
     *   as a [DataResult.Failure]; anything else - an exception from your own code, a bug the boundary counts
     *   as fatal - keeps going up. A `NullPointerException` in the block is a bug in the block, and turning it
     *   into a value would only hide it.
     *
     * Everything else is [transaction]'s: the receiver, the propagation, the per-thread binding.
     *
     * @param propagation What to do about a transaction already running on this thread.
     * @param isolation The isolation level to run at, or `null` for the server's.
     * @param readOnly Whether the transaction refuses writes.
     * @param statementTimeout Aborts any single statement running longer than this.
     * @param transactionTimeout Aborts the transaction once it has been open longer than this.
     * @param block The work to run in the transaction.
     * @return What the block produced, or the failure that rolled it back.
     */
    fun <T> transactionResult(
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        isolation: TransactionIsolationLevel? = null,
        readOnly: Boolean = false,
        statementTimeout: Duration? = null,
        transactionTimeout: Duration? = null,
        block: OctaviusClient.() -> DataResult<T>
    ): DataResult<T> = try {
        transaction(propagation, isolation, readOnly, statementTimeout, transactionTimeout) {
            when (val result = block()) {
                is DataResult.Success -> result
                // The transaction commits on a normal return, so a failure has to leave as an exception or it
                // would be committed alongside the work it was meant to undo. Caught below and handed back as
                // the value it already was.
                is DataResult.Failure -> throw RollbackSignal(result)
            }
        }
    } catch (signal: RollbackSignal) {
        signal.failure
    } catch (e: OctaviusException) {
        // Raised by the transaction itself rather than returned by the block: a commit that failed, a session
        // that could not be obtained, or a terminal the block let throw.
        if (isCallerBug(e)) throw e else DataResult.Failure(e)
    }

    /**
     * Runs every step of [plan] in one transaction, in the order they were added, and returns what each produced.
     *
     * A step's parameters are resolved just before it runs, so a
     * [TransactionValue][io.github.octaviusframework.client.transaction.TransactionValue] pointing at an
     * earlier step gets the value that step actually produced. Anything else in the map is passed through.
     *
     * There is no `…Result` variant of this the way [transactionResult] is one of [transaction], and the
     * asymmetry has a reason: a block can *return* a failure, which is what a plain transaction would commit
     * over, while a plan's steps can only throw. So `dbResult { db.executeTransactionPlan(plan) }` is both
     * sufficient and correct.
     *
     * **Everything a step raises names that step** on its
     * [path][io.github.octaviusframework.driver.exception.OctaviusException.path] - resolving its parameters,
     * running its statement, mapping its result alike - and is otherwise the exception the driver threw, so
     * what is caught on and retried is unchanged. The number is where the step sits in the plan being run,
     * which after [TransactionPlan.addPlan][io.github.octaviusframework.client.transaction.TransactionPlan.addPlan]
     * is not the index its handle was created at;
     * [describe][io.github.octaviusframework.client.transaction.TransactionPlan.describe] is what turns it
     * back into a step.
     *
     * @param plan The steps to run.
     * @param propagation What to do about a transaction already running on this thread.
     * @param isolation The isolation level to run at, or `null` for the server's.
     * @param readOnly Whether the transaction refuses writes.
     * @param statementTimeout Aborts any single statement running longer than this.
     * @param transactionTimeout Aborts the transaction once it has been open longer than this.
     * @return Every step's result, by handle.
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `INVALID_ARGUMENT` where
     * a step's query cannot be rendered or a step binds a handle whose step is not ahead of it in this plan,
     * both found before any step runs.
     */
    fun executeTransactionPlan(
        plan: TransactionPlan,
        propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
        isolation: TransactionIsolationLevel? = null,
        readOnly: Boolean = false,
        statementTimeout: Duration? = null,
        transactionTimeout: Duration? = null
    ): TransactionPlanResult {
        val steps = plan.steps()
        // Nothing to run is not a transaction worth opening
        if (steps.isEmpty()) return TransactionPlanResult(emptyMap(), 0)

        // Before the transaction rather than inside it, so a plan that was malformed when it arrived is
        // refused without any of it having run.
        // The index it builds is kept: a failure while resolving a parameter names the step it came from,
        // and after addPlan a handle's own index is not where its step sits here.
        val stepIndices = validatePlan(steps)

        return transaction(propagation, isolation, readOnly, statementTimeout, transactionTimeout) {
            val results = LinkedHashMap<StepHandle<*>, Any?>(steps.size)
            for ((index, step) in steps.withIndex()) {
                try {
                    results[step.handle] = step.run(resolveParams(step.params, results, index, stepIndices))
                } catch (e: OctaviusException) {
                    e.path.add("step $index")
                    throw e
                }
            }
            TransactionPlanResult(results, steps.size)
        }
    }

    /**
     * Releases what this client holds. A data source it was given rather than built is not closed.
     */
    override fun close()

    companion object {

        /**
         * Builds a client over [dataSource], running its own transactions on the calling thread.
         *
         * This is the standalone case: a connection pool, and nothing else deciding when transactions begin
         * and end. Where a framework owns transactions itself, give it a [SessionProvider] that finds the
         * session where that framework put it, through [fromSessionProvider], or the two will each open one.
         *
         * @param dataSource Where connections come from.
         * @param ownsDataSource Whether [close] should close [dataSource] as well. Leave it `false` for a pool
         * the application built and shuts down itself.
         * @param dynamicJson How `dynamic_dto` payloads are read and written. Only consulted where dynamic
         * types are registered at all. The default is
         * [octaviusJson][io.github.octaviusframework.serializer.octaviusJson]: strict, and carrying the
         * contextual serializers a `BigDecimal` or an unbounded date needs to survive JSON.
         * @param dynamicWriteStrategy When an unwrapped instance of a registered class is written as a
         * `dynamic_dto`.
         * @return A client ready to use.
         */
        fun fromDataSource(
            dataSource: DataSource,
            ownsDataSource: Boolean = false,
            dynamicJson: Json = octaviusJson,
            dynamicWriteStrategy: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
        ): OctaviusClient {
            val provider = DefaultSessionProvider(dataSource)
            return OctaviusClientImpl(
                provider,
                onClose = {
                    provider.close()
                    if (ownsDataSource) (dataSource as? AutoCloseable)?.close()
                },
                dynamicJson = dynamicJson,
                dynamicWriteStrategy = dynamicWriteStrategy
            )
        }

        /**
         * Builds a client over a [SessionProvider] of your own.
         *
         * The seam for anything that decides which session an operation belongs on other than a `ThreadLocal`:
         * a framework's transaction manager, or a test harness that pins every query to one connection and
         * rolls it back at the end.
         *
         * @param provider Decides which session each operation runs on.
         * @param ownsProvider Whether [close] should close [provider] as well.
         * @param dynamicJson How `dynamic_dto` payloads are read and written. Only consulted where dynamic
         * types are registered at all. The default is
         * [octaviusJson][io.github.octaviusframework.serializer.octaviusJson]: strict, and carrying the
         * contextual serializers a `BigDecimal` or an unbounded date needs to survive JSON.
         * @param dynamicWriteStrategy When an unwrapped instance of a registered class is written as a
         * `dynamic_dto`.
         * @return A client ready to use.
         */
        fun fromSessionProvider(
            provider: SessionProvider,
            ownsProvider: Boolean = true,
            dynamicJson: Json = octaviusJson,
            dynamicWriteStrategy: DynamicWriteStrategy = DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS
        ): OctaviusClient = OctaviusClientImpl(
            provider,
            onClose = if (ownsProvider) ({ provider.close() }) else null,
            dynamicJson = dynamicJson,
            dynamicWriteStrategy = dynamicWriteStrategy
        )
    }
}
