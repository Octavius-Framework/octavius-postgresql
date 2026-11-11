package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** The transaction state that refused the statement. */
enum class TransactionStateExceptionReason {
    /** An earlier error aborted the transaction, which refuses everything until rolled back (25P02). */
    IN_FAILED_TRANSACTION,

    /** A write was attempted inside a read-only transaction (25006). */
    READ_ONLY_TRANSACTION,

    /** The statement cannot run where it arrived in the transaction (25P01, 25001, 3B001). */
    MISPLACED_STATEMENT,

    /** Another class-25 or class-3B state, including the two-phase-commit codes the driver does not otherwise touch. */
    UNKNOWN
}

/**
 * The statement is fine and the transaction it arrived in is not. Under `IN_FAILED_TRANSACTION` the failure to
 * look for is the earlier one. The class-25 timeouts end the session: [SessionTerminatedException].
 *
 * @property dbMessage The server's message.
 * @property hint The server's hint.
 */
class TransactionStateException(
    val reason: TransactionStateExceptionReason,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("TRANSACTION_STATE_EXCEPTION:${reason.name}", sqlState, serverErrorMessage) {

    val dbMessage: String get() = serverErrorMessage!!.message
    val hint: String? get() = serverErrorMessage!!.hint

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("DB Message: $dbMessage")
        if (hint != null) appendLine("Hint: $hint")
    }
}

private fun generateDeveloperMessage(reason: TransactionStateExceptionReason): String =
    when (reason) {
        TransactionStateExceptionReason.IN_FAILED_TRANSACTION -> "The transaction was aborted by an earlier error and ignores every statement until it is rolled back. The failure to look for is that earlier one."
        TransactionStateExceptionReason.READ_ONLY_TRANSACTION -> "The transaction is read-only and refuses to write."
        TransactionStateExceptionReason.MISPLACED_STATEMENT -> "The statement cannot run where it arrived in the transaction. See the DB message for why."
        TransactionStateExceptionReason.UNKNOWN -> "The session's transaction state does not permit this statement."
    }
