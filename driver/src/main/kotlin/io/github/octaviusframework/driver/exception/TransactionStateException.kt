package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** The transaction state that refused the statement. */
enum class TransactionStateExceptionReason {
    /** An earlier error aborted the transaction, which refuses everything until rolled back (25P02). */
    IN_FAILED_TRANSACTION,

    /** A write was attempted inside a read-only transaction (25006). */
    READ_ONLY_TRANSACTION,

    /** The statement requires a transaction and none is open (25P01). */
    NO_ACTIVE_TRANSACTION,

    /** The statement refuses to run inside a transaction block, and one is open (25001). */
    ACTIVE_TRANSACTION,

    /** Another class-25 state, including the two-phase-commit codes the driver does not otherwise touch. */
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
        TransactionStateExceptionReason.NO_ACTIVE_TRANSACTION -> "The statement requires an open transaction and there is none."
        TransactionStateExceptionReason.ACTIVE_TRANSACTION -> "The statement cannot run inside a transaction block, and one is open."
        TransactionStateExceptionReason.UNKNOWN -> "The session's transaction state does not permit this statement."
    }
