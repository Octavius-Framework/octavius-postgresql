package io.github.octaviusframework.client

import io.github.octaviusframework.driver.exception.CodecException
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.RoutineAssertionException
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.TransactionStateException
import io.github.octaviusframework.driver.exception.TypeException

/**
 * Runs [block] and returns what it produced as a [DataResult]: a failure the application is expected to handle
 * becomes [DataResult.Failure], and one that means the calling code is wrong is still thrown.
 *
 * One query has [asResult][io.github.octaviusframework.client.query.RunnableQuery.asResult] and a transaction
 * [transactionResult][OctaviusClient.transactionResult]; this is for anything else.
 *
 * ```kotlin
 * val senators: DataResult<List<Senator>> = dbResult {
 *     db.execute {
 *         createNativeQuery("SET LOCAL statement_timeout = 5000").execute()
 *         createNamedQuery("SELECT id, cognomen FROM senators WHERE province_id = @p")
 *             .fetchObjects<Senator>("p" to 7)
 *     }
 * }
 * ```
 *
 * Not around the queries inside a [transaction][OctaviusClient.transaction]: the block then ends normally and the
 * transaction goes on to commit. [transactionResult][OctaviusClient.transactionResult] rolls back on a returned
 * failure instead.
 */
inline fun <T> dbResult(crossinline block: () -> T): DataResult<T> =
    try {
        DataResult.Success(block())
    } catch (e: OctaviusException) {
        if (isCallerBug(e)) throw e else DataResult.Failure(e)
    }

/**
 * Whether this failure says the calling code is wrong, so that the result style throws it rather than returning
 * it. Decided by the exception's type, never by its `reason`.
 *
 * A `fetch*Strict` that found no row is thrown: `Strict` asserted the row was there. [RoutineAssertionException]
 * is the same assertion written in PL/pgSQL, while a routine's `RAISE EXCEPTION` is a business rule and is
 * returned. [TransactionStateException] `IN_FAILED_TRANSACTION` means an earlier failure was turned into a value
 * and the work went on. [InitializationException] comes before any operation a result would describe. Every type
 * not listed is returned, those the driver gains later included.
 */
@PublishedApi
internal fun isCallerBug(e: OctaviusException): Boolean = when (e) {
    is StatementException,
    is TransactionStateException,
    is RoutineAssertionException,
    is MappingException,
    is TypeException,
    is CodecException,
    is InvalidOperationException,
    is InitializationException -> true

    else -> false
}
