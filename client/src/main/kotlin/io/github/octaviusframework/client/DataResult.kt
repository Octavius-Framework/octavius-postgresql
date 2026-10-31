package io.github.octaviusframework.client

import io.github.octaviusframework.driver.exception.OctaviusException

/**
 * The outcome of a database operation: a value, or the failure that replaced it.
 *
 * The result style - [dbResult], [transactionResult][OctaviusClient.transactionResult] and
 * [asResult][io.github.octaviusframework.client.query.RunnableQuery.asResult] - returns one for the failures the
 * database reports: a violated constraint, a deadlock, a routine's `RAISE EXCEPTION`, a timeout. A failure that
 * means the calling code is wrong is thrown instead.
 *
 * @param T The type of the value carried on success.
 */
sealed class DataResult<out T> {

    /** The operation completed and produced [value]. */
    data class Success<out T>(val value: T) : DataResult<T>()

    /** The database reported [error] and the operation produced no value. */
    data class Failure(val error: OctaviusException) : DataResult<Nothing>()
}

/** Applies [transform] to the value of a [DataResult.Success], and returns a [DataResult.Failure] as it stands. */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(value))
    is DataResult.Failure -> this
}

/** Runs [action] on the carried value if this is a [DataResult.Success], and returns this result either way. */
inline fun <T> DataResult<T>.onSuccess(action: (T) -> Unit): DataResult<T> {
    if (this is DataResult.Success) action(value)
    return this
}

/** Runs [action] on the failure if this is a [DataResult.Failure], and returns this result either way. */
inline fun <T> DataResult<T>.onFailure(action: (OctaviusException) -> Unit): DataResult<T> {
    if (this is DataResult.Failure) action(error)
    return this
}

/** Returns the carried value, or `null` where the operation failed. */
fun <T> DataResult<T>.getOrNull(): T? = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> null
}

/** Returns the carried value, or throws the carried failure. */
fun <T> DataResult<T>.getOrThrow(): T = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> throw error
}

/**
 * Returns the carried value, or what [onFailure] makes of the whole [DataResult.Failure] - which it can return as
 * it is, for an early exit:
 *
 * ```kotlin
 * val id = insertCitizen().getOrElse { return@transactionResult it }
 * ```
 */
inline fun <R, T : R> DataResult<T>.getOrElse(onFailure: (DataResult.Failure) -> R): R = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> onFailure(this)
}
