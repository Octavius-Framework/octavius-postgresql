package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * Exception thrown when the server stops a statement before it finishes - a `statement_timeout`, or a cancel
 * request such as `session.cancelQuery()` (57014).
 *
 * What ends is the statement, not the session: the server abandons it, reports this, and the connection goes on
 * to the next one. Inside a transaction the transaction is doomed as after any other error, until it is rolled
 * back. A timeout that ends the session itself - `transaction_timeout`, `idle_in_transaction_session_timeout`,
 * `idle_session_timeout` - is [SessionTerminatedException] instead, since there the connection goes too.
 *
 * @param sqlState The SQL state code returned by the database.
 * @param serverErrorMessage The original error message from the database server.
 */
class ExecutionAbortedException(
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("EXECUTION_ABORTED_EXCEPTION", sqlState, serverErrorMessage) {

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: The statement was cancelled - by statement_timeout, or by a cancel request.")
        if (serverErrorMessage?.message != null) appendLine("DB message: ${serverErrorMessage.message}")
    }
}
