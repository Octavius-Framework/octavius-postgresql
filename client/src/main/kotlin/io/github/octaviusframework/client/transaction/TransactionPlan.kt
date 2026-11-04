package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * Operations to run as one transaction, assembled before any of it runs - for a sequence decided in one place and
 * run in another. Where the sequence is written out where it runs, a `transaction { }` block is simpler.
 *
 * Steps run in the order they were added, and a step uses what an earlier one produced through the [StepHandle]
 * [add] returned:
 *
 * ```kotlin
 * val plan = TransactionPlan()
 *
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
 *
 * val results = db.executeTransactionPlan(plan)
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

    /** Appends [step], built with [RunnableQuery.asStep], and returns the handle later steps refer to it by. */
    fun <T> add(step: TransactionStep<T>): StepHandle<T> {
        val handle = StepHandle<T>(entries.size)
        entries += PlannedStep(handle, step.query, step.params, step.run)
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

        val alreadyHere = entries.mapTo(HashSet()) { it.handle }
        for (step in other.entries) {
            if (step.handle in alreadyHere) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "${step.handle} is already a step of this plan. A plan merged in twice - " +
                        "directly, or through two plans that both hold it - would run its steps twice and " +
                        "leave only the last result of each reachable."
                )
            }
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
        val run: (Map<String, Any?>) -> Any?
    )
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
                details = "$handle did not come from the plan that produced this result."
            )
        }
        return results[handle] as T
    }
}
