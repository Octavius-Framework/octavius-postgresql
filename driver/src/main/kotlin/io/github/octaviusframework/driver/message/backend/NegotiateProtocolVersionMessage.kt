package io.github.octaviusframework.driver.message.backend

/**
 * `NegotiateProtocolVersion`, from a server on an older minor version than the 3.2 asked for.
 *
 * @property newestVersion The newest version it speaks, as the full word: major in the upper 16 bits.
 * @property unrecognizedOptions The `_pq_.` options it did not recognize.
 */
internal class NegotiateProtocolVersionMessage(
    val newestVersion: Int,
    val unrecognizedOptions: List<String>
) : BackendMessage
