package io.github.octaviusframework.driver.message.backend

/**
 * Sent by a server that speaks an older protocol minor version than the startup message asked for, or does not
 * recognise some of the protocol options in it. It arrives before authentication, and the login then carries on
 * over the version it names.
 *
 * @property newestVersion The newest version the server speaks, as the full protocol version word - major in the
 *   upper 16 bits, minor in the lower. PostgreSQL and the poolers in front of it send it that way, although the protocol
 *   documentation describes the field as the minor version alone.
 * @property unrecognizedOptions The `_pq_.` options from the startup message that the server did not recognise.
 */
internal class NegotiateProtocolVersionMessage(
    val newestVersion: Int,
    val unrecognizedOptions: List<String>
) : BackendMessage
