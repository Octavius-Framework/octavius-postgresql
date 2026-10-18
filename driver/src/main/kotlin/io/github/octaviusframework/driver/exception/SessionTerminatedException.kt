package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * Why the server ended the session.
 */
enum class SessionTerminatedExceptionReason {
    /**
     * The transaction ran past what the server allows it - open longer than `transaction_timeout` (25P04), or
     * sitting open with nothing running longer than `idle_in_transaction_session_timeout` (25P03).
     */
    TRANSACTION_TIMEOUT,

    /** The session sat idle outside a transaction for longer than `idle_session_timeout` allows (57P05). */
    IDLE_SESSION_TIMEOUT,

    /** An administrator ended it - `pg_terminate_backend()`, or the server shutting down (57P01). */
    ADMINISTRATOR_COMMAND,

    /** Any other `FATAL` or `PANIC`; `sqlState` and `serverErrorMessage` say which. */
    UNKNOWN
}

/**
 * Exception thrown when the server ends the session: it sent a `FATAL` or a `PANIC`, and closed the connection
 * behind it.
 *
 * The session goes with it: nothing sent on it afterwards reaches a server, a pool evicts the connection, and
 * whatever the transaction had not committed is gone. Retrying means starting again on a new session. A statement
 * the server cancels is [ExecutionAbortedException] instead, and leaves the session usable.
 *
 * The type is decided by the severity rather than by the SQLSTATE, because the severity is what says the
 * connection is gone - whatever the code, a `FATAL` is the last thing the server sends. The code names the cause
 * for the ones an application would tell apart, and [SessionTerminatedExceptionReason.UNKNOWN] carries the rest
 * with the code intact.
 *
 * Only a session that was open is terminated. A `FATAL` during login is the server refusing one, and is an
 * [InitializationException].
 *
 * @property reason Why the server ended the session.
 * @property dbMessage The primary error message the server raised.
 * @param sqlState The SQL state code returned by the database.
 * @param serverErrorMessage The original error message from the database server.
 */
class SessionTerminatedException(
    val reason: SessionTerminatedExceptionReason,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("SESSION_TERMINATED_EXCEPTION:${reason.name}", sqlState, serverErrorMessage) {

    val dbMessage: String get() = serverErrorMessage!!.message

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("DB Message: $dbMessage")
    }
}

private fun generateDeveloperMessage(reason: SessionTerminatedExceptionReason): String =
    when (reason) {
        SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT -> "The transaction ran past transaction_timeout or idle_in_transaction_session_timeout, and the server ended the session with it."
        SessionTerminatedExceptionReason.IDLE_SESSION_TIMEOUT -> "The session sat idle for longer than idle_session_timeout allows, and the server ended it."
        SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND -> "An administrator ended the session - pg_terminate_backend(), or the server shutting down."
        SessionTerminatedExceptionReason.UNKNOWN -> "The server ended the session with a fatal error."
    }
