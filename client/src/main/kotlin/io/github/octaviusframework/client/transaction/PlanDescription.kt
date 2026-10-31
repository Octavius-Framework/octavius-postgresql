package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.client.query.RunnableQuery
import io.github.octaviusframework.driver.exception.InvalidOperationException

/**
 * Renders a value as the chain that will produce it, source first, never with a literal's value. A handle is
 * rendered by its place in [stepIndices] where given; its own `toString` has the index it was created at, which
 * after [TransactionPlan.addPlan] may not be where its step sits.
 */
internal fun describeValue(
    value: TransactionValue<*>,
    stepIndices: Map<StepHandle<*>, Int>? = null
): String = when (value) {
    is TransactionValue.Value -> "literal"

    is TransactionValue.FromStep ->
        stepIndices?.get(value.handle)?.let { "step $it" } ?: value.handle.toString()

    is TransactionValue.Transformed<*, *> ->
        "${describeValue(value.source, stepIndices)}.map(#${value.position})"
}

/** As [describeValue], for the one thing that goes among a step's parameters without being one. */
private fun describeParameter(value: Any?, stepIndices: Map<StepHandle<*>, Int>): String = when (value) {
    is SpreadParameters -> "spread of ${describeValue(value.source, stepIndices)}, this name dropped"
    is TransactionValue<*> -> describeValue(value, stepIndices)
    else -> "literal"
}

/** What a plan will do, as text: one block per step with its index, its SQL, and where each parameter comes from. */
internal fun describePlan(entries: List<TransactionPlan.PlannedStep>): String {
    if (entries.isEmpty()) return "TransactionPlan, no steps"

    val stepIndices = HashMap<StepHandle<*>, Int>(entries.size)
    entries.forEachIndexed { index, step -> stepIndices[step.handle] = index }

    return buildString {
        appendLine("TransactionPlan, ${entries.size} ${if (entries.size == 1) "step" else "steps"}")

        entries.forEachIndexed { index, step ->
            appendLine()
            appendLine("step $index")
            appendLine(renderSql(step.query).prependIndent("  "))

            // Padded to the longest name so the arrows line up and the sources read as a column.
            val width = step.params.keys.maxOfOrNull { it.length } ?: 0
            for ((name, value) in step.params) {
                appendLine("  @${name.padEnd(width)} <- ${describeParameter(value, stepIndices)}")
            }
        }
    }.trimEnd()
}

/** A step's SQL, or in its place why it cannot be rendered, so the other steps are still described. */
private fun renderSql(query: RunnableQuery<*>): String =
    try {
        query.toSql().trim()
    } catch (e: InvalidOperationException) {
        "<this query cannot be rendered: ${e.details}>"
    }
