package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage


/** What happened to the connection. */
enum class NetworkExceptionReason {
    /** Any other I/O failure. */
    CONNECTION_ERROR,
    /** A read or a connect timed out. */
    CONNECTION_TIMEOUT,
    /** The connection or session was already closed here. */
    CONNECTION_CLOSED,
    /** The server closed the connection. */
    CONNECTION_CLOSED_BY_PEER,
    /** The connection was aborted here, by `abort()` or an interrupt. */
    CONNECTION_ABORTED
}

/** The connection failed or was already closed. */
class NetworkException(
    val reason: NetworkExceptionReason,
    val details: String? = null,
    cause: Throwable? = null,
    sqlState: String? = "08006",
    serverErrorMessage: ServerErrorMessage? = null,
) : OctaviusException("NETWORK_EXCEPTION:${reason.name}", sqlState, serverErrorMessage, cause) {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (details != null) appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: NetworkExceptionReason): String =
    when (reason) {
        NetworkExceptionReason.CONNECTION_ERROR -> "A network error occurred while communicating with the database."
        NetworkExceptionReason.CONNECTION_TIMEOUT -> "The network connection to the database timed out."
        NetworkExceptionReason.CONNECTION_CLOSED -> "Operation cannot be performed because the connection is closed."
        NetworkExceptionReason.CONNECTION_CLOSED_BY_PEER -> "The connection was unexpectedly closed by the peer."
        NetworkExceptionReason.CONNECTION_ABORTED -> "The connection was explicitly aborted by the client."
    }

