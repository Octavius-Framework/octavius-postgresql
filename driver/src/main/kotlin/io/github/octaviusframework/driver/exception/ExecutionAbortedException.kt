package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * The server stopped a statement for `statement_timeout` or a cancel request (57014). The session stays usable; a
 * transaction is aborted as after any error.
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
