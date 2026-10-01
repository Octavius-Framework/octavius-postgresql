package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.PasswordMessage
import io.github.oshai.kotlinlogging.KotlinLogging



/**
 * Handles the authentication process during the PostgreSQL connection startup phase.
 * It manages the state machine for exchanging authentication messages with the server.
 */
internal object Authenticator {

    private val logger = KotlinLogging.logger {}

    /**
     * Authenticates the user with the PostgreSQL server using the provided credentials.
     *
     * SCRAM-SHA-256 is the mechanism this driver is built around, with or without channel binding.
     * A cleartext password is answered too, but only over an encrypted connection - see
     * [sendCleartextPassword] for what that is worth and why the condition is there. MD5 is refused
     * outright: PostgreSQL has deprecated it, and a driver that starts at 18 has no era to support
     * it for.
     *
     * @param stream The underlying PostgreSQL communication stream used for message exchange.
     * @param password The password for the user, can be null if not required.
     * @param channelBinding How hard to insist that the exchange be bound to the TLS channel.
     * @throws InitializationException If authentication fails, protocol is violated, or unsupported mechanism is requested.
     */
    fun authenticate(stream: PgStream, password: String?, channelBinding: ChannelBinding) {
        var boundToChannel = false

        while (true) {
            val msg = stream.receiveMessage()

            when (msg) {
                is AuthenticationMessage.Ok -> {
                    // A server can wave a connection through without asking anything - `trust`, or a
                    // client certificate already accepted during the handshake. That is a perfectly
                    // good login, but it is not a bound one, and REQUIRE means REQUIRE.
                    if (channelBinding == ChannelBinding.REQUIRE && !boundToChannel) {
                        throw InitializationException(
                            InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                            details = "channelBinding=require, but the server accepted this connection without " +
                                "a channel-bound authentication exchange."
                        )
                    }
                    logger.trace { "Authentication successful!" }
                    // Loop will continue to consume ParameterStatus until ReadyForQuery
                }

                is AuthenticationMessage.SASL -> {
                    boundToChannel = ScramSha256Authenticator.authenticate(
                        stream, password, msg.mechanisms, channelBinding
                    )
                }

                is AuthenticationMessage.CleartextPassword -> {
                    sendCleartextPassword(stream, password, channelBinding)
                }

                is AuthenticationMessage.MD5Password -> {
                    throw InitializationException(
                        InitializationExceptionReason.UNSUPPORTED_PASSWORD_ENCRYPTION,
                        details = "Server requested MD5Password, only SCRAM is supported"
                    )
                }

                is ErrorOrNoticeMessage -> {
                    throw ExceptionTranslator.translate(msg)
                }

                is NegotiateProtocolVersionMessage -> {
                    val minor = negotiatedMinorVersion(msg)
                    logger.trace { "Server speaks protocol 3.$minor; continuing on it" }
                }

                is BackendKeyDataMessage -> {
                    stream.processId = msg.processId
                    stream.secretKey = msg.secretKey
                    logger.trace { "Received process keys: ${msg.processId}" }
                }

                is ReadyForQueryMessage -> {
                    // Reported as one line rather than one per parameter: the whole set arrives in
                    // a burst, and it is the same set on every connection to the same server, so
                    // eighteen lines of it ahead of every login say nothing the set does not.
                    logger.trace {
                        "Server ready for queries; session parameters: " +
                            stream.parameters.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    }
                    // From here on a ParameterStatus is a change rather than part of the burst,
                    // and the stream reports each one on its own.
                    stream.startupComplete = true
                    return // End of login phase
                }

                is ParameterStatusMessage -> { /* Already recorded in stream.parameters; logged as a set above */ }

                else -> {
                    logger.trace { "Ignoring unexpected message: $msg" }
                }
            }
        }
    }

    /**
     * The minor version to carry on with once the server has named an older one than the 3.2 asked for.
     *
     * PostgreSQL 18 speaks 3.2 and never sends this. What does is a server or a pooler that speaks 3.0 -
     * PostgreSQL 17, or a pooler in front of 18 - and the answer is taken rather than refused, because
     * nothing in the driver depends on 3.2 itself: its one change is a longer cancel key, and the key is kept
     * at whatever length it arrives. What the driver does need is PostgreSQL 18 behind whatever answered,
     * and that is checked after login, where the server's own parameters say so.
     *
     * The version comes as the full protocol version word. A bare minor number is read too, because the
     * protocol documentation describes the field that way and a server written from it sends one. Anything
     * outside protocol 3, or newer than what was asked for, is not a negotiation this driver started.
     */
    private fun negotiatedMinorVersion(msg: NegotiateProtocolVersionMessage): Int {
        val version = msg.newestVersion
        val major = if (version ushr 16 != 0) version ushr 16 else 3
        val minor = version and 0xFFFF
        if (major != 3 || minor > 2) {
            throw InitializationException(
                InitializationExceptionReason.PROTOCOL_VIOLATION,
                details = "Asked for protocol 3.2, the server answered with protocol $major.$minor."
            )
        }
        return minor
    }

    /**
     * Answers an `AuthenticationCleartextPassword` request, but only where answering it is safe.
     *
     * The server asks for a password in the clear when `pg_hba.conf` names `password`, `ldap`, `pam`
     * or `radius` - the three latter because the server has to hold the password itself to check it
     * against something that is not PostgreSQL. There is no protocol on the client side to make that
     * safer: the password goes out as it is, and the only thing standing between it and the wire is
     * the encryption underneath. So the driver requires that encryption rather than trusting the
     * deployment to have arranged it, which is the one part of this exchange it can actually decide.
     *
     * A `channelBinding=require` connection is refused here rather than after the password has been
     * sent. No cleartext exchange can be bound to the channel, so this login is already lost by the
     * time the server would accept it; sending the credential first would give it away for nothing.
     */
    private fun sendCleartextPassword(stream: PgStream, password: String?, channelBinding: ChannelBinding) {
        if (!stream.isSecure) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_PASSWORD_ENCRYPTION,
                details = "The server asked for a cleartext password, which this connection is not " +
                    "encrypted enough to send. Raise sslmode to require or above, so the password is " +
                    "not readable on the wire."
            )
        }

        if (channelBinding == ChannelBinding.REQUIRE) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                details = "The server asked for a cleartext password, which cannot be bound to the TLS " +
                    "channel, but channelBinding=require was specified. The password was not sent."
            )
        }

        if (password == null) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_PASSWORD_ENCRYPTION,
                details = "The server asked for a cleartext password, but no password was configured."
            )
        }

        logger.trace { "Server requested a cleartext password; sending it over the encrypted connection" }
        stream.sendMessage(PasswordMessage(password))
        stream.flush()
    }
}
