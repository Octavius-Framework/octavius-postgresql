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
 * Hands out queries and sessions, and runs transactions over them.
 *
 * A query from [rawQuery], [select], [insertInto], [update] or [deleteFrom] finds its own session when a terminal
 * runs, so nothing has to be opened around it. [execute] hands over the driver's [OctaviusSessionOperations] for
 * work that is not a query - `copy`, `largeObjects`, `notifications`, several statements on one session.
 *
 * ```kotlin
 * val db = OctaviusClient.fromDataSource(hikariDataSource)
 *
 * val senators = db.rawQuery("SELECT id, cognomen FROM senators WHERE province_id = @p")
 *     .fetchObjects<Senator>("p" to 7)
 * ```
 *
 * Either way the session joins the transaction running on this thread, if there is one, so the same repository
 * function works standalone and inside a larger transaction.
 *
 * Failures are thrown. Where one should be a value,
 * [asResult][io.github.octaviusframework.client.query.RunnableQuery.asResult] covers one query, [transactionResult]
 * a transaction, and [dbResult] anything else.
 */
interface OctaviusClient : AutoCloseable {

    /**
     * Runs [block] on a session: inside a [transaction] on this thread the transaction's, otherwise one borrowed
     * for this call, whose statements commit as they go.
     */
    fun <T> execute(block: OctaviusSessionOperations.() -> T): T

    /** The `dynamic_dto` types this client knows. Nothing reaches the database until a type is registered. */
    val dynamicTypes: DynamicTypes

    /** SQL written by hand, with `@name` parameters. Every terminal call runs it, so calling two runs it twice. */
    fun rawQuery(sql: String): RawQuery

    /** Starts a `SELECT` of [columns], each passed through as SQL: `"id"`, `"count(*) AS total"`. */
    fun select(vararg columns: String): SelectQuery

    /** Starts an `INSERT` into [table]. */
    fun insertInto(table: String): InsertQuery

    /** Starts an `UPDATE` of [table], which renders only with a `WHERE`. */
    fun update(table: String): UpdateQuery

    /** Starts a `DELETE` from [table], which renders only with a `WHERE`. */
    fun deleteFrom(table: String): DeleteQuery

    /**
     * Runs [block] in a transaction, committing when it returns and rolling back when it throws. The receiver
     * offers no commit or rollback of its own.
     *
     * Every query and [execute] from this thread while [block] runs lands on the transaction's session. Work
     * handed to another thread does not: a coroutine on another dispatcher gets a session and a transaction of
     * its own.
     *
     * @param isolation `null` for the server's. Ignored when joining a running transaction, as is [readOnly].
     * @param statementTimeout Aborts any single statement running longer than this.
     * @param transactionTimeout Aborts the transaction once it has been open longer than this.
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
     * [transaction] for the result style. A plain [transaction] rolls back only on a throw, so a failure turned
     * into a value inside it is committed over; here a returned [DataResult.Failure] rolls back.
     *
     * ```kotlin
     * val created = db.transactionResult {
     *     val id = rawQuery("INSERT INTO citizens (name) VALUES (@n) RETURNING id")
     *         .asResult().fetchFieldStrict<Int>("n" to name)
     *         .getOrElse { return@transactionResult it }
     *
     *     rawQuery("INSERT INTO citizen_profiles (citizen_id, bio) VALUES (@id, @bio)")
     *         .asResult().executeUpdate("id" to id, "bio" to bio)
     *         .map { id }
     * }
     * ```
     *
     * - [DataResult.Success] commits and is returned.
     * - [DataResult.Failure] rolls back and is returned.
     * - A throw rolls back. What [dbResult] would return comes back as a [DataResult.Failure]; the rest - an
     *   exception from your own code, a caller bug - keeps going up.
     *
     * Everything else is as on [transaction].
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
                // Thrown so the transaction rolls back; caught below and handed back as it was.
                is DataResult.Failure -> throw RollbackSignal(result)
            }
        }
    } catch (signal: RollbackSignal) {
        signal.failure
    } catch (e: OctaviusException) {
        // A commit that failed, a session that could not be obtained, or a terminal the block let throw.
        if (isCallerBug(e)) throw e else DataResult.Failure(e)
    }

    /**
     * Runs every step of [plan] in one transaction, in order, and returns what each produced. A step's
     * [TransactionValue][io.github.octaviusframework.client.transaction.TransactionValue]s are resolved just
     * before it runs.
     *
     * Whatever a step raises is the driver's own exception with `step N` on its
     * [path][io.github.octaviusframework.driver.exception.OctaviusException.path], `N` being the step's place in
     * this plan; [describe][io.github.octaviusframework.client.transaction.TransactionPlan.describe] shows which
     * step that is. A step can only throw, so `dbResult { db.executeTransactionPlan(plan) }` is the result style
     * here.
     *
     * The other parameters are as on [transaction].
     *
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `INVALID_ARGUMENT`, before
     * any step runs, where a step's query cannot be rendered or a step binds a handle whose step is not ahead of
     * it in this plan.
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

        // Before the transaction, so a malformed plan runs none of it. The index names steps in failures:
        // after addPlan a handle's own index is not where its step sits here.
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
         * A client over [dataSource] that runs its own transactions, bound to the calling thread. Where a
         * framework owns transactions, use [fromSessionProvider] with a provider that finds its session, or each
         * will open one.
         *
         * @param ownsDataSource Whether [close] closes [dataSource] too.
         * @param dynamicJson How `dynamic_dto` payloads are read and written, as [DynamicTypes.json].
         * @param dynamicWriteStrategy When an unwrapped instance of a registered class is written as a
         * `dynamic_dto`.
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
         * A client over a [SessionProvider] of your own: a framework's transaction manager, or a test harness
         * that pins every query to one connection.
         *
         * @param ownsProvider Whether [close] closes [provider] too.
         * @param dynamicJson As on [fromDataSource].
         * @param dynamicWriteStrategy As on [fromDataSource].
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
