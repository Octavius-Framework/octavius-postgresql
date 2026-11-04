package io.github.octaviusframework.driver.exception

/** Why an operation was refused. */
enum class InvalidOperationExceptionReason {
    /** A commit, rollback or savepoint with auto-commit on. */
    AUTO_COMMIT_VIOLATION,
    /**
     * A commit, or auto-commit switched back on, in a transaction an earlier error aborted. Nothing is sent; roll
     * back.
     */
    COMMIT_OF_FAILED_TRANSACTION,
    /** A savepoint the driver did not create, a `null` name, or a name or number asked of one without it. */
    INVALID_SAVEPOINT,
    /** A session, statement, large object or `COPY` handle used after it was closed or finished. */
    RESOURCE_CLOSED,
    /** An argument the operation does not accept; `details` names it. */
    INVALID_ARGUMENT,
    /** A `@name` the statement uses and the values omit; `details` names it. */
    MISSING_NAMED_PARAMETER,
    /** A JDBC `unwrap` to an interface this object does not implement. */
    UNWRAP_ERROR,
    /** A JDBC feature the driver does not implement. */
    FEATURE_NOT_SUPPORTED,
    /** Rows returned to a call that expects none. */
    UNEXPECTED_RESULT,
    /** A row count the terminal forbids: none for a `fetch*Strict`, several for a single-row fetch. */
    INCORRECT_RESULT_SIZE,
    /**
     * The connection is busy with a `COPY` or a result still being read, or, for a listener loop, an open
     * transaction. `details` says which.
     */
    CONNECTION_BUSY
}

/**
 * An operation the driver refuses in the current state, or a call it cannot carry out. Raised by the driver, not
 * the server, so it has no `sqlState`.
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
        InvalidOperationExceptionReason.RESOURCE_CLOSED -> "The session, statement, Large Object or COPY handle this was asked of is already closed. See the details for which; handles are single-use, so the answer is a new one."
        InvalidOperationExceptionReason.INVALID_ARGUMENT -> "An argument passed to this operation is not acceptable. See the details for the value the driver rejected."
        InvalidOperationExceptionReason.MISSING_NAMED_PARAMETER -> "The statement names a parameter the supplied values do not include. See the details for its name."
        InvalidOperationExceptionReason.UNWRAP_ERROR -> "Cannot unwrap the connection/statement to the requested interface."
        InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED -> "This feature is not supported by the Octavius Driver."
        InvalidOperationExceptionReason.UNEXPECTED_RESULT -> "Execution returned a result set (rows) when none were expected. Use a fetch* method for DQL statements like SELECT."
        InvalidOperationExceptionReason.INCORRECT_RESULT_SIZE -> "The query returned a number of rows the chosen fetch method forbids. See the details for what was expected and what arrived."
        InvalidOperationExceptionReason.CONNECTION_BUSY -> "This connection is already carrying something that has to finish first - an exchange, or for a listener loop a transaction. See the details for what is running and how to let it finish."
    }
