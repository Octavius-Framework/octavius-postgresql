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
import java.security.cert.X509Certificate
import java.util.*
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The SCRAM-SHA-256 exchange of RFC 7677, bound to the TLS channel (`-PLUS`) or not. */
internal object ScramSha256Authenticator {
    private const val HMAC_SHA256 = "HmacSHA256"

    private const val MECHANISM = "SCRAM-SHA-256"
    private const val MECHANISM_PLUS = "SCRAM-SHA-256-PLUS"

    /** The only channel binding type PostgreSQL implements. */
    private const val BINDING_TYPE = "tls-server-end-point"

    private val logger = KotlinLogging.logger {}

    /** The client proof and the server signature to expect, both base64. */
    data class ScramResult(val clientProof: String, val expectedServerSignature: String)

    /**
     * Authenticates with the mechanism [mechanisms] and [channelBinding] allow, and returns whether it was bound.
     *
     * @throws InitializationException if no mechanism fits, the certificate cannot be bound to, the server breaks
     *   the protocol, or its signature does not verify.
     */
    fun authenticate(
        stream: PgStream,
        password: String?,
        mechanisms: List<String>,
        channelBinding: ChannelBinding
    ): Boolean {
        // Non-null only over TLS with binding allowed.
        val certificate = if (channelBinding == ChannelBinding.DISABLE) null else stream.peerCertificate
        val bindingData = if (certificate != null && mechanisms.contains(MECHANISM_PLUS)) {
            TlsServerEndPoint.hash(certificate) ?: throw unbindable(certificate)
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
            // "I can bind and you did not offer it": the server refuses this, so a downgrade in transit fails.
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

        // The server nonce must extend the client's, or a replayed message would pass.
        if (!serverNonce.startsWith(clientNonce)) {
            throw InitializationException(
                InitializationExceptionReason.PROTOCOL_VIOLATION,
                details = "The nonce in serverFirstMessage does not begin with the nonce this client sent."
            )
        }

        // c= carries the gs2-header and, when binding, the certificate hash.
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

        // What makes the exchange mutual: the server proves it knows the stored key.
        if (serverFinal.attribute("v", "serverFinalMessage") != scramResult.expectedServerSignature) {
            throw InitializationException(
                InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS,
                details = "Invalid server signature"
            )
        }

        return bindingData != null
    }

    /**
     * The refusal of a certificate signed without a digest, such as Ed25519 or Ed448. It applies under
     * [ChannelBinding.PREFER] as well as [ChannelBinding.REQUIRE], because falling back to unbound would let an
     * intermediary with such a certificate relay the login.
     */
    private fun unbindable(certificate: X509Certificate) = InitializationException(
        InitializationExceptionReason.UNSUPPORTED_MECHANISM,
        details = "The server certificate is signed with ${certificate.sigAlgName}, a signature made without a " +
            "digest, so channel binding has nothing to hash it with, on either end. Set channelBinding=disable " +
            "to log in unbound, or give the server a certificate signed with RSA or ECDSA."
    )

    /** The next message as a [T]; an `ErrorResponse` raises as the server's refusal. */
    private inline fun <reified T : AuthenticationMessage> expect(stream: PgStream): T {
        val message = stream.receiveMessage()
        if (message is ErrorOrNoticeMessage) throw ExceptionTranslator.translateRefusal(message)
        return message as? T ?: throw InitializationException(
            InitializationExceptionReason.PROTOCOL_VIOLATION,
            details = "Expected ${T::class.simpleName}, got: $message"
        )
    }

    /** A SCRAM message's `x=value` attributes; [attribute] insists on the ones needed. */
    private fun attributesOf(message: String): Map<String, String> =
        message.split(",")
            .filter { it.length >= 2 && it[1] == '=' }
            .associate { it.substring(0, 1) to it.substring(2) }

    private fun Map<String, String>.attribute(name: String, messageName: String): String =
        this[name] ?: throw InitializationException(
            InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER,
            details = "Missing $name in $messageName"
        )

    /** A random alphanumeric client nonce. */
    fun generateClientNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).replace(Regex("[^a-zA-Z0-9]"), "")
    }

    /** The client proof and expected server signature, as RFC 5802 computes them. */
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
