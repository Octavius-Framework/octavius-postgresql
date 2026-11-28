package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.backend.AuthenticationMessage
import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage
import io.github.octaviusframework.driver.message.frontend.SASLInitialResponse
import io.github.octaviusframework.driver.message.frontend.SASLResponse
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.oshai.kotlinlogging.KotlinLogging

/** The OAUTHBEARER exchange of RFC 7628, with a token from an [OAuthTokenProvider] and no discovery. */
internal object OAuthBearerAuthenticator {
    const val MECHANISM = "OAUTHBEARER"

    private const val KVSEP = '\u0001'

    /** RFC 6750's `b64token`, all PostgreSQL accepts; it leaves no room for a separator, so no key-value pair rides in. */
    private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")

    private val logger = KotlinLogging.logger {}

    /**
     * Sends the token [provider] gives for [request] and consumes the server's answer up to `AuthenticationOk`. The
     * provider is not asked where the token could not be sent.
     *
     * @throws InitializationException `WITHHELD_CREDENTIALS` outside TLS or without a provider;
     *   `UNSUPPORTED_MECHANISM` under `channelBinding=require`; `TOKEN_PROVIDER_FAILED` if the provider throws or
     *   returns no bearer token; `SERVER_REJECTED_CREDENTIALS` if the server refuses the token.
     */
    fun authenticate(
        stream: PgStream,
        provider: OAuthTokenProvider?,
        request: OAuthTokenRequest,
        channelBinding: ChannelBinding
    ) {
        // The token is the credential itself, readable on the wire to anyone who can read the connection.
        if (!stream.isSecure) {
            throw InitializationException(
                InitializationExceptionReason.WITHHELD_CREDENTIALS,
                details = "The server asks for an OAuth bearer token, which this connection is not encrypted " +
                    "enough to send. Raise sslmode to require or above, and to verify-full so the token goes only " +
                    "to the server named. No token was asked for."
            )
        }

        if (channelBinding == ChannelBinding.REQUIRE) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                details = "The server asks for an OAuth bearer token, which cannot be bound to the TLS channel, " +
                    "but channelBinding=require was specified. No token was asked for."
            )
        }

        if (provider == null) {
            throw InitializationException(
                InitializationExceptionReason.WITHHELD_CREDENTIALS,
                details = "The server asks for an OAuth bearer token, and no oauthTokenProvider or " +
                    "oauthTokenProviderClassName is configured."
            )
        }

        val token = try {
            provider.token(request)
        } catch (e: Exception) {
            throw InitializationException(
                InitializationExceptionReason.TOKEN_PROVIDER_FAILED,
                details = "The OAuth token provider ${provider.javaClass.name} failed for $request.",
                cause = e
            )
        }

        if (!BEARER_TOKEN.matches(token)) {
            // The token itself stays out of the message, as it stays out of the logs.
            throw InitializationException(
                InitializationExceptionReason.TOKEN_PROVIDER_FAILED,
                details = if (token.startsWith("Bearer ", ignoreCase = true)) {
                    "The OAuth token provider ${provider.javaClass.name} returned the token with its 'Bearer ' " +
                        "scheme in front. Return the token alone; nothing was sent."
                } else {
                    "The OAuth token provider ${provider.javaClass.name} returned a string that is not a bearer " +
                        "token: one or more of the letters, digits and -._~+/, then any number of '='. Nothing " +
                        "was sent."
                }
            )
        }

        logger.trace { "Authenticating with $MECHANISM" }
        stream.writeMessage(SASLInitialResponse(MECHANISM, "n,,${KVSEP}auth=Bearer $token$KVSEP$KVSEP"))
        stream.flush()

        when (val response = stream.receiveMessage()) {
            is AuthenticationMessage.Ok -> logger.trace { "Authentication successful!" }

            // A refused token. RFC 7628 has the client answer with a lone separator, and the server then ends
            // the login with its error.
            is AuthenticationMessage.SASLContinue -> {
                stream.writeMessage(SASLResponse(KVSEP.toString()))
                stream.flush()

                val end = stream.receiveMessage()
                if (end is ErrorOrNoticeMessage) throw ExceptionTranslator.translateRefusal(end)
                throw InitializationException(
                    InitializationExceptionReason.PROTOCOL_VIOLATION,
                    details = "The server refused the OAuth bearer token, then sent $end in place of its error."
                )
            }

            is ErrorOrNoticeMessage -> throw ExceptionTranslator.translateRefusal(response)

            else -> throw InitializationException(
                InitializationExceptionReason.PROTOCOL_VIOLATION,
                details = "Expected AuthenticationOk or a refusal after the OAuth bearer token, got: $response"
            )
        }
    }
}
