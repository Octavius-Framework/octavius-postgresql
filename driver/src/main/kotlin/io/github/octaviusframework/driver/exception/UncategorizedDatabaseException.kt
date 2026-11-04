package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** A server error no other exception covers. */
class UncategorizedDatabaseException(
    val details: String,
    sqlState: String? = null,
    serverErrorMessage: ServerErrorMessage? = null
) : OctaviusException("UNCATEGORIZED_DATABASE_EXCEPTION", sqlState, serverErrorMessage) {
    override fun getDetailedMessage(): String = details
}
