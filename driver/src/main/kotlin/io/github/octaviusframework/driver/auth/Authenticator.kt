package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.PasswordMessage
import io.github.oshai.kotlinlogging.KotlinLogging



/** The login exchange, up to the first `ReadyForQuery`. */
internal object Authenticator {

    private val logger = KotlinLogging.logger {}

    /**
     * Logs in with SCRAM-SHA-256, or a cleartext password over TLS; MD5 is refused.
     *
     * @throws InitializationException if the login is refused or fails.
     */
    fun authenticate(stream: PgStream, password: String?, channelBinding: ChannelBinding) {
        var boundToChannel = false

        while (true) {
            val msg = stream.receiveMessage()

            when (msg) {
                is AuthenticationMessage.Ok -> {
                    // A login with no exchange, `trust` or a client certificate, is not a bound one.
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
                    throw ExceptionTranslator.translateRefusal(msg)
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
                    // One line for the whole burst.
                    logger.trace {
                        "Server ready for queries; session parameters: " +
                            stream.parameters.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    }
                    // From here on the stream logs each ParameterStatus as a change.
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
     * The minor version a pooler or server on 3.0 named instead of 3.2, accepted since nothing depends on 3.2;
     * PostgreSQL 18 is checked after login. Read as the full version word or a bare minor number.
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
     * Sends the password a `password`, `ldap`, `pam` or `radius` login asks for, only over TLS, and refuses it under
     * `channelBinding=require` before anything is sent.
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
