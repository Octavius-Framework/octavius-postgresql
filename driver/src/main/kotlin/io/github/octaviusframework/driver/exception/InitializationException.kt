package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

// ------------------- INITIALIZATION -------------------

/** Why a connection could not be opened. */
enum class InitializationExceptionReason {
    /**
     * The server asked for an authentication mechanism the driver does not support, or for one that cannot be
     * bound to the TLS channel under `channelBinding=require`.
     */
    UNSUPPORTED_MECHANISM,
    /** The server broke the protocol during login. */
    PROTOCOL_VIOLATION,
    /** The server's authentication challenge or session parameters lacked one the driver needs. */
    MISSING_PROTOCOL_PARAMETER,
    /** The server rejected the user, the password or the OAuth token. */
    SERVER_REJECTED_CREDENTIALS,
    /**
     * The OAuth token provider could not be loaded, threw, or returned something that is not a bearer token;
     * what it threw is the cause.
     */
    TOKEN_PROVIDER_FAILED,
    /**
     * The driver withheld the credentials the server asked for: an MD5 password, or a cleartext password or an
     * OAuth token outside TLS or with none configured. Nothing was sent.
     */
    WITHHELD_CREDENTIALS,
    /** TLS was refused or failed, or a certificate did not verify. */
    SSL_ERROR,
    /** The server is older than PostgreSQL 18. */
    UNSUPPORTED_SERVER_VERSION,
    /**
     * The server could not be reached, refused the login for a reason other than the credentials (its code in
     * `sqlState`), or none of several servers could be used (each in `details`).
     */
    CONNECTION_ERROR,

    /** A pool had no free connection in time; nothing reached the server. `details` carries what the pool said. */
    CONNECTION_UNAVAILABLE
}

/** A connection could not be opened or authenticated. */
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
        InitializationExceptionReason.UNSUPPORTED_MECHANISM -> "The server asked for an authentication mechanism this connection cannot use."
        InitializationExceptionReason.PROTOCOL_VIOLATION -> "Unexpected message received during authentication protocol."
        InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER -> "Missing expected parameter in what the server sent at login."
        InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS -> "Authentication failed: the server rejected the credentials."
        InitializationExceptionReason.TOKEN_PROVIDER_FAILED -> "The OAuth token provider gave no token to log in with."
        InitializationExceptionReason.WITHHELD_CREDENTIALS -> "The driver withheld the credentials the server asked for on this connection."
        InitializationExceptionReason.SSL_ERROR -> "SSL negotiation failed or is not supported by the server."
        InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION -> "Unsupported PostgreSQL server version. Octavius requires version 18 or higher."
        InitializationExceptionReason.CONNECTION_ERROR -> "Could not connect to the database."
        InitializationExceptionReason.CONNECTION_UNAVAILABLE ->
            "No connection was available. The data source had none to give rather than failing to open one."
    }
