package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

// ------------------- INITIALIZATION -------------------

/**
 * Represents the specific reason for an initialization failure during database connection or authentication.
 */
enum class InitializationExceptionReason {
    /** The server requested or only supports an authentication mechanism not supported by this driver. */
    UNSUPPORTED_MECHANISM,
    /** The server sent an unexpected message sequence violating the connection protocol. */
    PROTOCOL_VIOLATION,
    /** A required parameter was missing in the server's authentication challenge. */
    MISSING_PROTOCOL_PARAMETER,
    /** The database server rejected the provided username or password. */
    SERVER_REJECTED_CREDENTIALS,
    /** The server requested an outdated/unsupported password encryption method (e.g. MD5, Cleartext). */
    UNSUPPORTED_PASSWORD_ENCRYPTION,
    /** SSL negotiation failed or certificates are invalid/unsupported. */
    SSL_ERROR,
    /** The server version is too old for this driver to support. */
    UNSUPPORTED_SERVER_VERSION,
    /** A generic connection error occurred before authentication could begin. */
    CONNECTION_ERROR,

    /**
     * The data source had no connection to give, rather than failing to open one - a pool that ran out of
     * time waiting for a free one, most often.
     *
     * Nothing reached the server. The database and the credentials are not what is in question here; there
     * was simply nothing free at that moment, which is a fact about the application's own demand for
     * connections. `details` carries what the data source said, which for a pool includes its own count of
     * how many were in play.
     */
    CONNECTION_UNAVAILABLE
}

/**
 * Exception thrown when the driver fails to establish a connection or authenticate with the database server.
 *
 * This typically occurs during the initial connection handshake and can be caused by network issues,
 * invalid credentials, unsupported protocols, or incompatible server versions.
 *
 * @property reason The specific type of initialization failure.
 * @property details Additional, human-readable details about the failure.
 * @param cause The underlying exception that caused this failure, if any.
 * @param sqlState The SQL state code returned by the database, if available.
 * @param serverErrorMessage The original error message from the database server, if available.
 */
class InitializationException(
    val reason: InitializationExceptionReason,
    val details: String? = null,
    cause: Throwable? = null,
    sqlState: String? = null,
    serverErrorMessage: ServerErrorMessage? = null
) : OctaviusException("INITIALIZATION_EXCEPTION:${reason.name}", cause = cause, sqlState = sqlState, serverErrorMessage = serverErrorMessage) {
    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        if (details != null) appendLine("Details: $details")
    }
}

private fun generateDeveloperMessage(reason: InitializationExceptionReason): String =
    when (reason) {
        InitializationExceptionReason.UNSUPPORTED_MECHANISM -> "Server does not support the required authentication mechanism (e.g., SCRAM-SHA-256)."
        InitializationExceptionReason.PROTOCOL_VIOLATION -> "Unexpected message received during authentication protocol."
        InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER -> "Missing expected parameter in the server's authentication message."
        InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS -> "Authentication failed: Invalid username or password."
        InitializationExceptionReason.UNSUPPORTED_PASSWORD_ENCRYPTION -> "Server requested an unsupported password encryption method (like Cleartext or MD5)."
        InitializationExceptionReason.SSL_ERROR -> "SSL negotiation failed or is not supported by the server."
        InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION -> "Unsupported PostgreSQL server version. Octavius requires version 18 or higher."
        InitializationExceptionReason.CONNECTION_ERROR -> "Could not connect to the database."
        InitializationExceptionReason.CONNECTION_UNAVAILABLE ->
            "No connection was available. The data source had none to give rather than failing to open one."
    }
