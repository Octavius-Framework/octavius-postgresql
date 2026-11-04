package io.github.octaviusframework.client

/**
 * Carries a [DataResult.Failure] out of a transaction that rolls back only on a throw. Built without a stack
 * trace, since it never reaches the caller.
 */
internal class RollbackSignal(val failure: DataResult.Failure) :
    RuntimeException(null, null, false, false)
