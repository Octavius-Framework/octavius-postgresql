package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.DeleteQuery
import io.github.octaviusframework.client.query.InsertQuery
import io.github.octaviusframework.client.query.RawQuery
import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.client.query.SelectQuery
import io.github.octaviusframework.client.query.UpdateQuery
import io.github.octaviusframework.client.session.SessionProvider
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.session.OctaviusSessionOperations

/**
 * Operations to run as one transaction, assembled before any of it runs and without a client - by code that
 * decides what to write, for a caller that runs it. Where the work is decided where it runs, a `transaction { }`
 * block is simpler.
 *
 * With the plan as the receiver, its query starters build the statements and [asStep] turns each into a step.
 * The step's terminal adds it to the plan and returns the [StepHandle] a later step takes its result through:
 *
 * ```kotlin
 * fun planEdict(edict: Map<String, Any?>, sealedBy: String): Pair<TransactionPlan, StepHandle<Int>> =
 *     with(TransactionPlan()) {
 *         val edictId = insertInto("edicts").values(edict).returning("id")
 *             .asStep().fetchFieldStrict<Int>(edict)
 *
 *         insertInto("edict_seals").values(listOf("edict_id", "sealed_by"))
 *             .asStep().executeUpdate("edict_id" to edictId.value(), "sealed_by" to sealedBy)
 *
 *         this to edictId
 *     }
 *
 * val (plan, edictId) = planEdict(edict, "Augustus")
 * val id = db.executeTransactionPlan(plan)[edictId]
 * ```
 *
 * Running a plan does not change it, so retrying a serialization failure is a loop around
 * [executeTransactionPlan][io.github.octaviusframework.client.OctaviusClient.executeTransactionPlan], each run
 * resolving its handles against its own results.
 */
class TransactionPlan {

    private val entries = mutableListOf<PlannedStep>()

    /** How many steps the plan holds. */
    val size: Int get() = entries.size

    /** Whether the plan holds no steps at all. */
    fun isEmpty(): Boolean = entries.isEmpty()

    /** SQL written by hand, with `@name` parameters, to become a step through [asStep]. */
    fun rawQuery(sql: String): RawQuery = RawQuery(PlanOnly, sql)

    /** Starts a `SELECT` of [columns], to become a step through [asStep]. */
    fun select(vararg columns: String): SelectQuery = SelectQuery(PlanOnly, columns.joinToString(", "))

    /** Starts an `INSERT` into [table], to become a step through [asStep]. */
    fun insertInto(table: String): InsertQuery = InsertQuery(PlanOnly, table)

    /** Starts an `UPDATE` of [table], which renders only with a `WHERE`, to become a step through [asStep]. */
    fun update(table: String): UpdateQuery = UpdateQuery(PlanOnly, table)

    /** Starts a `DELETE` from [table], which renders only with a `WHERE`, to become a step through [asStep]. */
    fun deleteFrom(table: String): DeleteQuery = DeleteQuery(PlanOnly, table)

    /**
     * Turns this query into a step of this plan, which the terminal called on the result adds. A client's query
     * works too: every step runs on the transaction that executes the plan.
     */
    fun RunnableQuery<*>.asStep(): StepBuilder = StepBuilder(this@TransactionPlan, this)

    /** Appends a step and returns the handle later steps refer to it by. */
    @PublishedApi
    internal fun <T> addStep(
        query: RunnableQuery<*>,
        params: Map<String, Any?>,
        run: OctaviusSessionOperations.(Map<String, Any?>) -> T
    ): StepHandle<T> {
        val handle = StepHandle<T>()
        entries += PlannedStep(handle, query, params, run)
        return handle
    }

    /**
     * Appends every step of [other], in its order. [other]'s handles keep working against the merged plan and its
     * result, and [other] itself is left unchanged.
     *
     * A step takes values only from steps ahead of it, so [other]'s steps may use this plan's handles and not the
     * other way round. A plan merged the wrong way is refused when executed, before any of it runs.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where [other] is this plan, or holds a step this plan
     * already holds.
     */
    fun addPlan(other: TransactionPlan) {
        if (other === this) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "A plan cannot be merged into itself: every step would run twice under one handle."
            )
        }

        val indexHere = HashMap<StepHandle<*>, Int>(entries.size)
        entries.forEachIndexed { index, step -> indexHere[step.handle] = index }
        for ((index, step) in other.entries.withIndex()) {
            val here = indexHere[step.handle] ?: continue
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "Step $index of the plan being merged is already step $here of this plan. A plan merged " +
                    "in twice - directly, or through two plans that both hold it - would run its steps twice and " +
                    "leave only the last result of each reachable."
            )
        }

        entries += other.entries
    }

    /**
     * What this plan will do, as text: every step's index, its SQL, and where each parameter comes from.
     *
     * ```
     * TransactionPlan, 2 steps
     *
     * step 0
     *   INSERT INTO edicts (title, tribute) VALUES (@title, @tribute) RETURNING id
     *   @title   <- literal
     *   @tribute <- literal
     *
     * step 1
     *   INSERT INTO edict_items (edict_id, amount) VALUES (@edict_id, @amount)
     *   @edict_id <- step 0.map(#1)
     *   @amount   <- literal
     * ```
     *
     * Literal values are not shown; what a step ran with is on the
     * [queryContext][io.github.octaviusframework.driver.exception.OctaviusException.queryContext] of what it
     * threw. A step whose query cannot be rendered says so in place of its SQL.
     */
    fun describe(): String = describePlan(entries)

    override fun toString(): String = "TransactionPlan($size ${if (size == 1) "step" else "steps"})"

    internal fun steps(): List<PlannedStep> = entries.toList()

    /** A step and the handle it was filed under. */
    internal class PlannedStep(
        val handle: StepHandle<*>,
        val query: RunnableQuery<*>,
        val params: Map<String, Any?>,
        val run: OctaviusSessionOperations.(Map<String, Any?>) -> Any?
    )

    /** Behind a plan's own queries, which run only as steps, on the session the plan's execution hands them. */
    private object PlanOnly : SessionProvider {

        override fun <T> execute(action: OctaviusSessionOperations.() -> T): T = throw refusal()

        override fun <T> transaction(definition: TransactionDefinition, block: () -> T): T = throw refusal()

        private fun refusal() = InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "This query was built by a TransactionPlan and runs only as one of its steps: turn it into " +
                "a step with asStep() and run the plan with executeTransactionPlan."
        )
    }
}

/** What a [TransactionPlan] produced: every step's result, referred to or not. */
class TransactionPlanResult internal constructor(
    private val results: Map<StepHandle<*>, Any?>,
    /** How many steps ran. */
    val size: Int
) {

    /**
     * The result of the step [handle] names.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the handle is not from the plan that produced
     * this result.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(handle: StepHandle<T>): T {
        if (!results.containsKey(handle)) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "The handle did not come from the plan that produced this result."
            )
        }
        return results[handle] as T
    }
}
