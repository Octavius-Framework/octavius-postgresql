package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * The server failed on its own account - out of disk or memory, an internal error - rather than over the query.
 *
 * @property details The server's message, with the SQLSTATE in it.
 */
class DatabaseSystemException(
    val details: String,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException("DATABASE_SYSTEM_EXCEPTION", sqlState = sqlState, serverErrorMessage = serverErrorMessage) {
    override fun getDetailedMessage(): String = details
}