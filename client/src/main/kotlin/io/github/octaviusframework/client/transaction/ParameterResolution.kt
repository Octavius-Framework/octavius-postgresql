package io.github.octaviusframework.client.transaction

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException

/**
 * Replaces the unresolved values in a step's parameters with what the earlier steps produced. A
 * [SpreadParameters] contributes its entries under their own names, so the result can be longer than [raw].
 * Every failure names the step, since a plan built in a loop has the same SQL and parameters in every step.
 *
 * @param stepIndex Where this step sits in the plan being run.
 * @param stepIndices Where every step sits, for naming the step a value was taken from.
 */
internal fun resolveParams(
    raw: Map<String, Any?>,
    results: Map<StepHandle<*>, Any?>,
    stepIndex: Int,
    stepIndices: Map<StepHandle<*>, Int>
): Map<String, Any?> {
    if (raw.none { it.value is TransactionValue<*> || it.value is SpreadParameters }) return raw

    val resolved = LinkedHashMap<String, Any?>(raw.size)
    for ((name, value) in raw) {
        when (value) {
            is SpreadParameters -> resolved.putAll(spreadOf(value, results, name, stepIndex, stepIndices))
            is TransactionValue<*> -> resolved[name] = resolveOne(value, results, name, stepIndex, stepIndices)
            else -> resolved[name] = value
        }
    }
    return resolved
}

private fun resolveOne(
    value: TransactionValue<*>,
    results: Map<StepHandle<*>, Any?>,
    paramName: String,
    stepIndex: Int,
    stepIndices: Map<StepHandle<*>, Int>
): Any? = when (value) {
    is TransactionValue.Value -> value.value

    is TransactionValue.FromStep -> resultOf(value.handle, results, paramName, stepIndex)

    is TransactionValue.Transformed<*, *> -> {
        val input = resolveOne(value.source, results, paramName, stepIndex, stepIndices)
        @Suppress("UNCHECKED_CAST")
        val transform = value.transform as (Any?) -> Any?
        // The caller's own code: anything from outside the driver's hierarchy is wrapped, or the result style
        // would not see it.
        try {
            transform(input)
        } catch (e: OctaviusException) {
            // Rethrown as itself, so a retry still matches its type, with the map and the parameter on its path.
            // The executor adds the step.
            e.path.add("map #${value.position}")
            e.path.add("parameter '$paramName'")
            throw e
        } catch (e: Exception) {
            throw MappingException(
                MappingExceptionReason.CONVERSION_ERROR,
                details = "Step $stepIndex of the plan, parameter '$paramName': map #${value.position} over " +
                    "${describeValue(value.source, stepIndices)} threw ${e::class.simpleName}: ${e.message}",
                cause = e
            )
        }
    }
}

/** The entries a [SpreadParameters] contributes, which stand in for the name it was filed under. */
@Suppress("UNCHECKED_CAST")
private fun spreadOf(
    spread: SpreadParameters,
    results: Map<StepHandle<*>, Any?>,
    paramName: String,
    stepIndex: Int,
    stepIndices: Map<StepHandle<*>, Int>
): Map<String, Any?> =
    when (val resolved = resolveOne(spread.source, results, paramName, stepIndex, stepIndices)) {
        // Checked as far as erasure allows: the keys are String by the type spread() takes.
        is Map<*, *> -> resolved as Map<String, Any?>

        // Out of reach from Kotlin, spread() taking a non-null map: only erasure or a Java caller gets here.
        else -> throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Step $stepIndex of the plan spreads ${resolved?.let { it::class.simpleName } ?: "null"} " +
                "under '$paramName', which is not a Map."
        )
    }

private fun resultOf(
    handle: StepHandle<*>,
    results: Map<StepHandle<*>, Any?>,
    paramName: String,
    stepIndex: Int
): Any? {
    if (!results.containsKey(handle)) {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Step $stepIndex of the plan binds '$paramName' to $handle, which is a step that runs " +
                "after it or comes from another plan. A step can only use results from steps added before it."
        )
    }
    return results[handle]
}
