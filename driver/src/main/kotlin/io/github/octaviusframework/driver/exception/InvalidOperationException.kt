package io.github.octaviusframework.driver.exception

/**
 * Represents the specific reason for an invalid operation.
 */
enum class InvalidOperationExceptionReason {
    /** Transaction operations like commit or savepoint used when auto-commit is enabled. */
    AUTO_COMMIT_VIOLATION,
    /**
     * A commit asked of a transaction an earlier error already aborted - by `commit()`, or by switching
     * auto-commit back on, which commits. PostgreSQL would carry the `COMMIT` out as a `ROLLBACK` and report
     * nothing, so the driver refuses it instead of sending it: the transaction stays aborted, and rolling it back
     * is the way out, as it is for every other statement there.
     */
    COMMIT_OF_FAILED_TRANSACTION,
    /** Provided savepoint is invalid or belongs to another connection. */
    INVALID_SAVEPOINT,
    /**
     * The handle being used is closed or finished - a statement, a Large Object, a `COPY` that has already
     * ended. Handles are not reopened; `details` names the one at fault and the answer is always a new one.
     */
    RESOURCE_CLOSED,
    /**
     * An argument supplied by the caller is not acceptable - out of range, null, or unsupported.
     * Covers negative timeouts, a null SQL string, an unknown isolation level, a `PgTyped` wrapping
     * another `PgTyped`, a `PgRecord` read out of a result and handed back as a bound parameter, and
     * the like. The offending value is named in `details`; there is nothing to branch on here, since
     * no caller can react to its own bad argument at runtime.
     */
    INVALID_ARGUMENT,
    /**
     * The statement names a parameter the supplied values do not include. A `@name` with nothing to put
     * there is the call being incomplete rather than the SQL being wrong, which is why it is here and not
     * on [StatementException]; `details` names the parameter.
     */
    MISSING_NAMED_PARAMETER,
    /** JDBC unwrap() failed. */
    UNWRAP_ERROR,
    /** The JDBC feature is not implemented by this driver. */
    FEATURE_NOT_SUPPORTED,
    /** update or execute returned result. */
    UNEXPECTED_RESULT,
    /**
     * The query returned a number of rows the chosen terminal forbids - a `fetch*Strict` that found none,
     * or a single-row fetch that found several. The statement ran; what does not fit is the terminal picked
     * for it, which makes this [UNEXPECTED_RESULT]'s neighbour rather than a statement failure.
     */
    INCORRECT_RESULT_SIZE,
    /**
     * The connection is already carrying an exchange and can carry only one - a `COPY` still running, or a
     * statement still being read. `details` says which, and what to do about it.
     */
    CONNECTION_BUSY
}

/**
 * Exception thrown when the driver attempts an operation that is not allowed in the current state or context.
 *
 * Examples include trying to commit when auto-commit is enabled, operating on a closed statement,
 * using unsupported features, or asking a query for something the call itself did not make possible.
 *
 * Purely client-side: nothing here was reported by the server, so there is no `sqlState` and no
 * `serverErrorMessage`. Raised inside a query, it still carries the
 * [queryContext][OctaviusException.queryContext] - the SQL and the parameters - the way every driver
 * exception does.
 *
 * @property reason The specific type of invalid operation.
 * @property details Additional details about the failure.
 */
class InvalidOperationException(
    val reason: InvalidOperationExceptionReason,
    val details: String? = null,
) : OctaviusException("INVALID_OPERATION_EXCEPTION:${reason.name}") {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (details != null) appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: InvalidOperationExceptionReason): String =
    when (reason) {
        InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION -> "Operation (like setting a savepoint or commit/rollback) is not allowed when auto-commit is enabled."
        InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION -> "An earlier error aborted this transaction, so a COMMIT would only roll it back. Nothing was sent: roll back, and look for that earlier failure."
        InvalidOperationExceptionReason.INVALID_SAVEPOINT -> "Invalid savepoint operation."
        InvalidOperationExceptionReason.RESOURCE_CLOSED -> "The statement, Large Object or COPY handle this was asked of is already closed. See the details for which; handles are single-use, so the answer is a new one."
        InvalidOperationExceptionReason.INVALID_ARGUMENT -> "An argument passed to this operation is not acceptable. See the details for the value the driver rejected."
        InvalidOperationExceptionReason.MISSING_NAMED_PARAMETER -> "The statement names a parameter the supplied values do not include. See the details for its name."
        InvalidOperationExceptionReason.UNWRAP_ERROR -> "Cannot unwrap the connection/statement to the requested interface."
        InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED -> "This feature is not supported by the Octavius Driver."
        InvalidOperationExceptionReason.UNEXPECTED_RESULT -> "Execution returned a result set (rows) when none were expected. Use query() for DQL statements like SELECT."
        InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE -> "The query returned a number of rows the chosen fetch method forbids. See the details for what was expected and what arrived."
        InvalidOperationExceptionReason.CONNECTION_BUSY -> "This connection is already carrying an exchange, and it can carry only one. See the details for what is running and how to let it finish."
    }
