package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * Represents the transaction state that made a statement inadmissible.
 */
enum class TransactionStateExceptionReason {
    /** The transaction is already doomed by an earlier error and rejects everything until it is rolled back (25P02). */
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
 * Exception thrown when the statement itself is fine and the transaction it arrived in is not.
 *
 * The server rejects these before the statement is considered on its own merits, which is why they are not
 * [StatementException]: nothing about the SQL is wrong, and the server sends no error position to point at.
 * What is wrong is the state around it - a transaction an earlier error already doomed, one declared
 * read-only, one open where the command forbids it, or none open where the command requires one.
 *
 * `25P02` is the one worth naming twice, because it is a consequence rather than a cause: after any error
 * inside an explicit transaction PostgreSQL discards the work and rejects every further command until a
 * `ROLLBACK` arrives. The failure to read is the *first* one; this one only says the session never left it.
 *
 * The two class-25 timeouts, `25P03` and `25P04`, are not here - they end the session rather than refuse a
 * statement, and a session the server ended is [SessionTerminatedException].
 *
 * @property reason The transaction state that refused the statement.
 * @property dbMessage The primary error message the server raised.
 * @property hint Explicit HINT field provided by PostgreSQL.
 * @param sqlState The SQL state code returned by the database.
 * @param serverErrorMessage The original error message from the database server.
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
