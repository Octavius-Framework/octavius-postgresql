package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * A sequence of operations to run as one transaction, assembled before any of it runs.
 *
 * A plan is for the case a `transaction { }` block does not cover: the sequence itself is data. Where one
 * layer decides what has to happen - a screen with a variable number of rows on it, a service turning a
 * request into operations - and another runs it, the block form would mean passing a lambda that closes over
 * everything it touched. A plan is a value; it can be built up, counted, inspected and handed on.
 *
 * Steps run in the order they were added, and a step may use what an earlier one produced through the
 * [StepHandle] that [add] returned:
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
 *             .asStep().update(
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
 * Where the sequence is fixed and written out in one place, a `transaction { }` block says the same thing in
 * fewer moving parts and with the values in plain Kotlin locals. Reach for a plan when the sequence is not
 * known where it is executed.
 *
 * Executing a plan does not consume it: the steps are copied out and the results kept in a map of the run's
 * own, with nothing written back. The same plan runs again unchanged, which is what makes retrying a
 * serialization failure or a deadlock a loop around
 * [executeTransactionPlan][io.github.octaviusframework.client.OctaviusClient.executeTransactionPlan] rather
 * than a rebuild - and each run resolves its handles against its own results, so the second run reads what the
 * second run produced.
 */
class TransactionPlan {

    private val entries = mutableListOf<PlannedStep>()

    /** How many steps the plan holds. */
    val size: Int get() = entries.size

    /** Whether the plan holds no steps at all. */
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Appends a step, and returns the handle later steps use to refer to what it will produce.
     *
     * @param step The operation, built with [io.github.octaviusframework.client.query.RunnableQuery.asStep].
     * @return A handle on this step's future result.
     */
    fun <T> add(step: TransactionStep<T>): StepHandle<T> {
        val handle = StepHandle<T>(entries.size)
        entries += PlannedStep(handle, step.query, step.params, step.run)
        return handle
    }

    /**
     * Appends every step of [other], in its order, after the steps already here.
     *
     * This is what makes a plan compose. One layer produces a plan for its own part of the work, another for
     * its part, and something above them runs the two as one transaction without having to know what either
     * put in. Handles handed out by [other] keep working against the merged plan and against
     * [TransactionPlanResult]: a result is filed under the handle itself rather than under a position, so
     * where a step ends up in the merged sequence changes nothing about how it is referred to.
     *
     * Where one plan uses the other's handles, the order is the one thing to get right. A step can take a value
     * only from a step ahead of it, so [other]'s steps may use handles from the steps already here and not the
     * other way round. Merged the other way round, the plan is refused when it is executed, before any of it
     * runs.
     *
     * [other] is not consumed and not changed - it can still be run on its own, or merged elsewhere.
     *
     * @param other The plan whose steps to take.
     * @throws InvalidOperationException `INVALID_ARGUMENT` where [other] is this plan, or holds a step this
     * plan already holds. Running one step twice under one handle would leave the first result unreachable,
     * the second having overwritten it.
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
     * The whole of what this plan will do, as text: every step's index, its SQL, and where each of its
     * parameters comes from.
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
     * This is the piece a plan needs and a `transaction { }` block does not. A block is read where it is
     * written; a plan is assembled by one layer and run by another, so the code holding it when it fails is
     * usually not the code that decided what went in - and a plan built in a loop has the same SQL in twenty
     * steps, which is what makes "step 17" in a failure worth being able to look up.
     *
     * What a literal *is* is deliberately not shown. The wiring is the part that cannot be read off the code
     * that assembled the plan; a bound value can, and printing one would put a `bytea` parameter or a column
     * of personal data wherever this was written to. The values a step actually ran with are on the
     * [queryContext][io.github.octaviusframework.driver.exception.OctaviusException.queryContext] of what it
     * threw, which is bounded for the purpose.
     *
     * A step whose query cannot be rendered says so in place of its SQL rather than throwing: a plan holding
     * one is among the things worth describing, and the other nineteen steps still describe.
     *
     * @return One block per step, in the order they will run.
     */
    fun describe(): String = describePlan(entries)

    override fun toString(): String = "TransactionPlan($size ${if (size == 1) "step" else "steps"})"

    internal fun steps(): List<PlannedStep> = entries.toList()

    /** A step and the handle it was filed under, which is all the executor needs of either. */
    internal class PlannedStep(
        val handle: StepHandle<*>,
        val query: RunnableQuery<*>,
        val params: Map<String, Any?>,
        val run: (Map<String, Any?>) -> Any?
    )
}

/**
 * What a [TransactionPlan] produced, by step.
 *
 * Every step's result is kept, whether or not anything referred to it, because the caller usually wants at
 * least one of them - the id that was generated - and which one that is only the caller knows.
 */
class TransactionPlanResult internal constructor(
    private val results: Map<StepHandle<*>, Any?>,
    /** How many steps ran. */
    val size: Int
) {

    /**
     * The result of the step [handle] names.
     *
     * @param handle The handle [TransactionPlan.add] returned for that step.
     * @return What that step produced.
     * @throws InvalidOperationException `INVALID_ARGUMENT` where the handle is from another plan, or from a
     * plan that has not been executed.
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
