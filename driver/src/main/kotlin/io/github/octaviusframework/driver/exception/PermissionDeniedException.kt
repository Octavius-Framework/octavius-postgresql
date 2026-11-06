package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * The user lacks a privilege the statement needs. The server names what it refused in [dbMessage] and, where it
 * says what the privilege takes, in [dbDetail].
 *
 * @property dbMessage The server's message.
 * @property dbDetail The server's detail.
 * @property hint The server's hint.
 */
class PermissionDeniedException(
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException(
    message = "PERMISSION_DENIED_EXCEPTION",
    sqlState = sqlState,
    serverErrorMessage = serverErrorMessage
) {
    val dbMessage: String get() = serverErrorMessage!!.message
    val dbDetail: String? get() = serverErrorMessage!!.detail
    val hint: String? get() = serverErrorMessage!!.hint
    override fun getDetailedMessage(): String = buildString {
        appendLine("Database message: $dbMessage")
        if (dbDetail != null) appendLine("Detail: $dbDetail")
        if (hint != null) appendLine("Hint: $hint")
    }
}
