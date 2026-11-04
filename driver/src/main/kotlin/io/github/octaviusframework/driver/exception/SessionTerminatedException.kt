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
 * The server ended an open session with a `FATAL` or `PANIC`, whatever the SQLSTATE. Uncommitted work is gone and
 * the connection with it; a retry needs a new session. A `FATAL` at login is an [InitializationException].
 *
 * @property dbMessage The server's message.
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
