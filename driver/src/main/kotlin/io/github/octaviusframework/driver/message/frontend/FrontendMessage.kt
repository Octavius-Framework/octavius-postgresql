package io.github.octaviusframework.driver.message.frontend

import io.github.octaviusframework.driver.io.PgOutputStream

/** A message to the server. */
internal sealed interface FrontendMessage {
    /** Writes the whole message, tag and length included. */
    fun encode(out: PgOutputStream)
}
