package io.github.octaviusframework.client

import io.github.octaviusframework.driver.exception.OctaviusException

/**
 * The outcome of a database operation, which either carries a value or the failure that replaced it.
 *
 * Every terminal method on a client query returns one of these rather than throwing, for the class of
 * failures the database itself reports: a constraint the row violated, a deadlock, a `RAISE EXCEPTION`
 * from a routine, a statement that ran out of time, a session the server ended. Those are conditions an
 * application is expected to have an answer for, and a return type says so where a `catch` two frames up
 * does not.
 *
 * It does not cover every way a call can fail. A query the database refused to parse, a row that does
 * not fit the class it was asked for, a type the registry has never heard of - those are bugs in the
 * calling code, and they are thrown. [OctaviusClient] names the split exactly.
 *
 * @param T The type of the value carried on success.
 */
sealed class DataResult<out T> {

    /** The operation completed and produced [value]. */
    data class Success<out T>(val value: T) : DataResult<T>()

    /** The database reported [error] and the operation produced no value. */
    data class Failure(val error: OctaviusException) : DataResult<Nothing>()
}

/**
 * Applies [transform] to the value of a [DataResult.Success], and returns a [DataResult.Failure] as it stands.
 *
 * @param transform The transformation to apply to the carried value.
 * @return The transformed result, or the original failure.
 */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(value))
    is DataResult.Failure -> this
}

/**
 * Runs [action] on the carried value if this is a [DataResult.Success], and returns this result either way.
 *
 * @param action What to do with the value.
 * @return This result, for chaining.
 */
inline fun <T> DataResult<T>.onSuccess(action: (T) -> Unit): DataResult<T> {
    if (this is DataResult.Success) action(value)
    return this
}

/**
 * Runs [action] on the failure if this is a [DataResult.Failure], and returns this result either way.
 *
 * @param action What to do with the error.
 * @return This result, for chaining.
 */
inline fun <T> DataResult<T>.onFailure(action: (OctaviusException) -> Unit): DataResult<T> {
    if (this is DataResult.Failure) action(error)
    return this
}

/**
 * Returns the carried value, or `null` where the operation failed.
 *
 * @return The value on success, `null` on failure.
 */
fun <T> DataResult<T>.getOrNull(): T? = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> null
}

/**
 * Returns the carried value, throwing the failure in its place.
 *
 * This gives up what the return type was for, so reach for it where the caller above this one is the
 * one holding the failure path - a `transaction` block, a test - rather than as the default way to read
 * a result.
 *
 * @return The value on success.
 * @throws OctaviusException The carried failure, where there is one.
 */
fun <T> DataResult<T>.getOrThrow(): T = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> throw error
}

/**
 * Returns the carried value, or what [onFailure] makes of the failure.
 *
 * [onFailure] receives the whole [DataResult.Failure] rather than the exception inside it, so it can be
 * returned as it stands - which is what makes the early exit out of a transaction block read the way it does:
 *
 * ```kotlin
 * val id = insertCitizen().getOrElse { return@transaction it }
 * ```
 *
 * @param onFailure What to produce from a failure.
 * @return The value on success, or the result of [onFailure].
 */
inline fun <R, T : R> DataResult<T>.getOrElse(onFailure: (DataResult.Failure) -> R): R = when (this) {
    is DataResult.Success -> value
    is DataResult.Failure -> onFailure(this)
}
