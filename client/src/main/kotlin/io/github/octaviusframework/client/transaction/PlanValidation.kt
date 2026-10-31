package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * Checks a plan before a transaction is opened, so a malformed one fails before any step has done its work: a
 * query that cannot render, and a handle naming no step ahead of it in this plan. The second can only come from
 * another plan - its handle, merged in the wrong order or not at all.
 *
 * @return Where each step sits, by handle, for naming steps in failures; after [TransactionPlan.addPlan] a
 * handle's own index is not where its step sits.
 * @throws InvalidOperationException `INVALID_ARGUMENT` where a step's query cannot render, or a step binds a
 * parameter to a handle from another plan or to a step that runs after it.
 */
internal fun validatePlan(steps: List<TransactionPlan.PlannedStep>): Map<StepHandle<*>, Int> {
    val indexByHandle = HashMap<StepHandle<*>, Int>(steps.size)
    steps.forEachIndexed { index, step -> indexByHandle[step.handle] = index }

    steps.forEachIndexed { index, step ->
        try {
            step.query.toSql()
        } catch (e: InvalidOperationException) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "Step $index of the plan has a query that cannot be rendered: ${e.details}"
            )
        }

        for ((name, value) in step.params) {
            when (value) {
                is TransactionValue<*> -> checkHandles(value, index, name, indexByHandle)
                is SpreadParameters -> checkHandles(value.source, index, name, indexByHandle)
            }
        }
    }

    return indexByHandle
}

/** Walks a value for the handles it depends on, through however many [TransactionValue.Transformed] wrap it. */
private fun checkHandles(
    value: TransactionValue<*>,
    stepIndex: Int,
    paramName: String,
    indexByHandle: Map<StepHandle<*>, Int>
) {
    when (value) {
        is TransactionValue.Value -> Unit
        is TransactionValue.Transformed<*, *> -> checkHandles(value.source, stepIndex, paramName, indexByHandle)
        is TransactionValue.FromStep -> {
            val source = indexByHandle[value.handle]
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Step $stepIndex binds '$paramName' to ${value.handle}, which is not a step of " +
                        "this plan. A handle is useful only inside the plan whose add() returned it."
                )
            if (source >= stepIndex) {
                throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Step $stepIndex binds '$paramName' to step $source, which runs after it. A step " +
                        "can only use results from steps ahead of it, so a plan using another plan's handles " +
                        "has to be merged in after that plan, not before it."
                )
            }
        }
    }
}
