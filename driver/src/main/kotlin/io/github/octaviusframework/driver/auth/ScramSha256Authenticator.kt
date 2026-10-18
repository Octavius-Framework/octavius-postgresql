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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Runs the SCRAM-SHA-256 exchange of RFC 7677, with or without the channel binding of RFC 5802.
 *
 * The two mechanisms are one piece of code because they differ in exactly two places: the name
 * sent to the server, and what goes into the `c=` attribute of the client-final-message.
 */
internal object ScramSha256Authenticator {
    private const val HMAC_SHA256 = "HmacSHA256"

    private const val MECHANISM = "SCRAM-SHA-256"
    private const val MECHANISM_PLUS = "SCRAM-SHA-256-PLUS"

    /** The only channel binding type PostgreSQL implements. */
    private const val BINDING_TYPE = "tls-server-end-point"

    private val logger = KotlinLogging.logger {}

    /**
     * Holds the results of a SCRAM signature computation.
     *
     * @property clientProof The computed client proof (base64 encoded).
     * @property expectedServerSignature The expected server signature for verification (base64 encoded).
     */
    data class ScramResult(val clientProof: String, val expectedServerSignature: String)

    /**
     * Authenticates over [stream] using whichever of the two mechanisms [mechanisms] and
     * [channelBinding] between them allow.
     *
     * @return true when the exchange was bound to the TLS channel, which is what lets the caller
     *   enforce [ChannelBinding.REQUIRE] over the whole login rather than over this step alone.
     * @throws InitializationException if no usable mechanism is on offer, the server violates the
     *   protocol, or its final signature does not verify.
     */
    fun authenticate(
        stream: PgStream,
        password: String?,
        mechanisms: List<String>,
        channelBinding: ChannelBinding
    ): Boolean {
        // Asking for the certificate is itself the test of whether binding is possible: it comes
        // back non-null only on an encrypted connection, and only when the caller allows binding.
        val certificate = if (channelBinding == ChannelBinding.DISABLE) null else stream.peerCertificate
        val bindingData = if (certificate != null && mechanisms.contains(MECHANISM_PLUS)) {
            TlsServerEndPoint.hash(certificate)
        } else {
            null
        }

        if (channelBinding == ChannelBinding.REQUIRE && bindingData == null) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                details = if (certificate == null) {
                    "channelBinding=require, but there is no server certificate to bind to - the connection is " +
                        "not encrypted, or the server presented no certificate. Set sslmode=require or stronger."
                } else {
                    "channelBinding=require, but the server does not offer $MECHANISM_PLUS. " +
                        "It offered: ${mechanisms.joinToString()}."
                }
            )
        }

        if (bindingData == null && !mechanisms.contains(MECHANISM)) {
            throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                details = "The server offers no SASL mechanism this driver supports. It offered: ${mechanisms.joinToString()}."
            )
        }

        val mechanism = if (bindingData != null) MECHANISM_PLUS else MECHANISM
        val gs2Header = when {
            bindingData != null -> "p=$BINDING_TYPE,,"
            // "I can do channel binding and you did not offer it." PostgreSQL always offers it on
            // an encrypted connection, so it reads this as the contradiction it is and answers
            // with "SCRAM channel binding negotiation error" - which is exactly what should happen
            // when the mechanism list was stripped down in transit to force the weaker exchange.
            certificate != null -> "y,,"
            else -> "n,,"
        }

        logger.trace { "Authenticating with $mechanism" }

        val clientNonce = generateClientNonce()
        val clientFirstMessageBare = "n=,r=$clientNonce"

        stream.sendMessage(SASLInitialResponse(mechanism, gs2Header + clientFirstMessageBare))
        stream.flush()

        val serverFirstMessage = String(expect<AuthenticationMessage.SASLContinue>(stream).data, StandardCharsets.UTF_8)
        val serverFirst = attributesOf(serverFirstMessage)

        val serverNonce = serverFirst.attribute("r", "serverFirstMessage")
        val salt = Base64.getDecoder().decode(serverFirst.attribute("s", "serverFirstMessage"))
        val iterations = serverFirst.attribute("i", "serverFirstMessage").toIntOrNull()
            ?: throw InitializationException(
                InitializationExceptionReason.PROTOCOL_VIOLATION,
                details = "Iteration count in serverFirstMessage is not a number: ${serverFirst["i"]}"
            )

        // The server nonce must extend the client's own. Without this check a replayed
        // server-first-message would be accepted as a fresh one.
        if (!serverNonce.startsWith(clientNonce)) {
            throw InitializationException(
                InitializationExceptionReason.PROTOCOL_VIOLATION,
                details = "The nonce in serverFirstMessage does not begin with the nonce this client sent."
            )
        }

        // c= carries the gs2-header the exchange opened with, and - when binding - the certificate
        // hash behind it. The server recomputes both from its own side of the connection.
        val cbindInput = gs2Header.toByteArray(StandardCharsets.UTF_8) + (bindingData ?: ByteArray(0))
        val clientFinalMessageWithoutProof = "c=${Base64.getEncoder().encodeToString(cbindInput)},r=$serverNonce"

        val scramResult = computeSignatures(
            password ?: "",
            salt,
            iterations,
            clientFirstMessageBare,
            serverFirstMessage,
            clientFinalMessageWithoutProof
        )

        stream.sendMessage(SASLResponse("$clientFinalMessageWithoutProof,p=${scramResult.clientProof}"))
        stream.flush()

        val serverFinalMessage = String(expect<AuthenticationMessage.SASLFinal>(stream).data, StandardCharsets.UTF_8)
        val serverFinal = attributesOf(serverFinalMessage)

        serverFinal["e"]?.let {
            throw InitializationException(
                InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS,
                details = "The server ended the SCRAM exchange with: $it"
            )
        }

        // Verifying this is what makes the exchange mutual: it proves the other end knows the
        // stored key, so a server that merely collected the proof cannot pass for the real one.
        if (serverFinal.attribute("v", "serverFinalMessage") != scramResult.expectedServerSignature) {
            throw InitializationException(
                InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS,
                details = "Invalid server signature"
            )
        }

        return bindingData != null
    }

    /**
     * Reads the next message, insisting it be a [T].
     *
     * An `ErrorResponse` is the server's ordinary way of saying no at any point in the exchange,
     * so it is translated rather than reported as a protocol violation.
     */
    private inline fun <reified T : AuthenticationMessage> expect(stream: PgStream): T {
        val message = stream.receiveMessage()
        if (message is ErrorOrNoticeMessage) throw ExceptionTranslator.translateRefusal(message)
        return message as? T ?: throw InitializationException(
            InitializationExceptionReason.PROTOCOL_VIOLATION,
            details = "Expected ${T::class.simpleName}, got: $message"
        )
    }

    /**
     * Splits a SCRAM message into its single-letter attributes. Anything not in `x=value` shape is
     * dropped here; whether the attributes that mattered came through is decided by [attribute].
     */
    private fun attributesOf(message: String): Map<String, String> =
        message.split(",")
            .filter { it.length >= 2 && it[1] == '=' }
            .associate { it.substring(0, 1) to it.substring(2) }

    private fun Map<String, String>.attribute(name: String, messageName: String): String =
        this[name] ?: throw InitializationException(
            InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER,
            details = "Missing $name in $messageName"
        )

    /**
     * Generates a random base64-encoded string to be used as a client nonce.
     * Non-alphanumeric characters are removed.
     *
     * @return A random client nonce string.
     */
    fun generateClientNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).replace(Regex("[^a-zA-Z0-9]"), "")
    }

    /**
     * Computes the client proof and expected server signature for SCRAM authentication.
     *
     * @param password The user's plaintext password.
     * @param salt The salt provided by the server.
     * @param iterations The iteration count provided by the server.
     * @param clientFirstMessageBare The bare version of the client-first-message (without gs2-header).
     * @param serverFirstMessage The server-first-message received from the server.
     * @param clientFinalMessageWithoutProof The client-final-message up to the "p=" attribute.
     * @return A [ScramResult] containing the computed `clientProof` and `expectedServerSignature`.
     */
    fun computeSignatures(password: String, salt: ByteArray, iterations: Int, clientFirstMessageBare: String, serverFirstMessage: String, clientFinalMessageWithoutProof: String): ScramResult {
        // 1. SaltedPassword = Hi(Normalize(password), salt, i)
        val saltedPassword = pbkdf2(password, salt, iterations)

        // 2. ClientKey = HMAC(SaltedPassword, "Client Key")
        val clientKey = hmac(saltedPassword, "Client Key".toByteArray())

        // 3. StoredKey = H(ClientKey)
        val storedKey = sha256(clientKey)

        // 4. AuthMessage = client-first-message-bare + "," + server-first-message + "," + client-final-message-without-proof
        val authMessage = "$clientFirstMessageBare,$serverFirstMessage,$clientFinalMessageWithoutProof"

        // 5. ClientSignature = HMAC(StoredKey, AuthMessage)
        val clientSignature = hmac(storedKey, authMessage.toByteArray())

        // 6. ClientProof = ClientKey XOR ClientSignature
        val clientProof = ByteArray(clientKey.size)
        for (i in clientKey.indices) {
            clientProof[i] = (clientKey[i].toInt() xor clientSignature[i].toInt()).toByte()
        }

        // 7. ServerKey = HMAC(SaltedPassword, "Server Key")
        val serverKey = hmac(saltedPassword, "Server Key".toByteArray())

        // 8. ServerSignature = HMAC(ServerKey, AuthMessage)
        val serverSignature = hmac(serverKey, authMessage.toByteArray())

        return ScramResult(
            Base64.getEncoder().encodeToString(clientProof),
            Base64.getEncoder().encodeToString(serverSignature)
        )
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return factory.generateSecret(spec).encoded
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }

    private fun sha256(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }
}
