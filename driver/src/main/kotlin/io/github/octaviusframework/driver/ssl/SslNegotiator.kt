package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.SSLRequestMessage
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.EncryptedPrivateKeyInfo
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.*
import javax.net.ssl.*

private val logger = KotlinLogging.logger {}

/** Starts TLS with the server and wraps the socket in it. */
internal object SslNegotiator {

    /**
     * The TLS settings [properties] ask for, kept past the handshake for cancel requests.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if `sslnegotiation=direct` comes with an `sslmode` below
     *   `require`.
     */
    fun configurationOf(properties: OctaviusProperties): SslConfiguration {
        val mode = properties.sslMode ?: if (properties.ssl == true) SslMode.REQUIRE else SslMode.PREFER
        val negotiation = properties.sslNegotiation ?: SslNegotiation.POSTGRES
        if (negotiation == SslNegotiation.DIRECT && (mode == SslMode.DISABLE || mode == SslMode.PREFER)) {
            // As libpq refuses it: a server that turns direct TLS away could only be retried in plaintext.
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "sslnegotiation=direct starts TLS without asking whether the server has it, so it needs " +
                    "sslmode=require or stronger, but sslmode=${mode.value} is in effect."
            )
        }
        return SslConfiguration(
            mode = mode,
            rootCertPath = properties.sslRootCert,
            certPath = properties.sslCert,
            keyPath = properties.sslKey,
            keyPassword = properties.sslPassword,
            negotiation = negotiation
        )
    }

    /**
     * Starts TLS on [stream] before the startup message. Under `sslnegotiation=direct` the handshake begins at once;
     * otherwise an `SSLRequest` asks first, and under `PREFER` a refusal goes on in plaintext. `DISABLE` asks nothing.
     * On failure [stream] is left for the caller to drop: a Terminate before the startup message is a packet the
     * server logs as malformed.
     *
     * @throws InitializationException `SSL_ERROR` if the server refuses under a mode that requires TLS, answers
     *   with neither yes nor no, or fails the handshake.
     */
    fun negotiate(stream: PgStream, host: String, port: Int, config: SslConfiguration) {
        if (config.mode == SslMode.DISABLE) return
        if (config.negotiation == SslNegotiation.DIRECT) {
            stream.upgradeToSSL(host, port, config)
            return
        }

        stream.writeMessage(SSLRequestMessage())
        stream.flush()

        when (val response = stream.inputStream.readByte().toInt().toChar()) {
            'S' -> stream.upgradeToSSL(host, port, config)
            'N' -> {
                if (config.mode != SslMode.PREFER) {
                    throw InitializationException(InitializationExceptionReason.SSL_ERROR, "Server does not support SSL, but sslmode=${config.mode.value} was specified.")
                }
                // For a cancel request's connection, which gets no connection line of its own.
                logger.trace { "Server declined SSL; continuing in plaintext under sslmode=prefer" }
            }
            else -> throw InitializationException(InitializationExceptionReason.SSL_ERROR, "Unexpected SSL negotiation response: $response")
        }
    }

    /**
     * Wraps [socket] in TLS 1.2 or 1.3 and completes the handshake: one the server agreed to upgrade, or one just
     * connected under `sslnegotiation=direct`, which offers ALPN `postgresql` and requires the server to take it.
     *
     * @throws InitializationException `SSL_ERROR` if the handshake or verification fails, or a direct handshake
     *   completes without ALPN `postgresql`.
     */
    fun upgrade(socket: Socket, host: String, port: Int, config: SslConfiguration): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")

        val trustManagers = createTrustManagers(config)
        val keyManagers = createKeyManagers(config)
        
        sslContext.init(keyManagers, trustManagers, SecureRandom())
        
        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(socket, host, port, true) as SSLSocket
        val direct = config.negotiation == SslNegotiation.DIRECT

        val sslParams = sslSocket.sslParameters
        if (config.mode == SslMode.VERIFY_FULL) {
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
        }
        // Only where the server demands it: offered after an SSLRequest too, it would fail at a proxy whose ALPN
        // list leaves postgresql out.
        if (direct) {
            sslParams.applicationProtocols = arrayOf(ALPN_PROTOCOL)
        }
        sslSocket.sslParameters = sslParams
        
        sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3").filter { it in sslSocket.supportedProtocols }.toTypedArray()
        
        val negotiation = if (direct) ", sslnegotiation=direct" else ""
        try {
            sslSocket.startHandshake()
        } catch (e: SSLException) {
            // A refused certificate among them. A timeout is not an SSLException and reaches the factory as one.
            val hint = if (direct) {
                ". A server or pooler that expects an SSLRequest first drops a direct handshake; " +
                    "sslnegotiation=postgres sends one."
            } else {
                ""
            }
            throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "TLS handshake with $host:$port failed under sslmode=${config.mode.value}$negotiation: ${e.message}$hint",
                e
            )
        }
        if (direct && sslSocket.applicationProtocol != ALPN_PROTOCOL) {
            throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "Direct TLS with $host:$port was established without the server agreeing to ALPN $ALPN_PROTOCOL, " +
                    "which PostgreSQL always agrees to. Something in between terminates TLS without it: configure it " +
                    "to accept $ALPN_PROTOCOL, or use sslnegotiation=postgres."
            )
        }
        // The connection line says it is encrypted; only the protocol and cipher are new here.
        logger.trace {
            "TLS established with $host:$port under sslmode=${config.mode.value}$negotiation " +
                "(${sslSocket.session.protocol}, ${sslSocket.session.cipherSuite})"
        }
        return sslSocket
    }

    /** The ALPN protocol name of PostgreSQL, without which a server refuses a direct handshake. */
    private const val ALPN_PROTOCOL = "postgresql"

    private fun createTrustManagers(config: SslConfiguration): Array<TrustManager>? {
        if (config.mode == SslMode.DISABLE) return null
        
        if (config.mode == SslMode.PREFER || config.mode == SslMode.REQUIRE) {
            return arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
        }
        
        if (config.rootCertPath != null) {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = FileInputStream(config.rootCertPath).use { cf.generateCertificates(it) }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            for ((index, cert) in certs.withIndex()) {
                keyStore.setCertificateEntry("ca-$index", cert)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            return tmf.trustManagers
        }
        
        return null // Use JVM default
    }

    private fun createKeyManagers(config: SslConfiguration): Array<KeyManager>? {
        if (config.certPath == null || config.keyPath == null) return null
        
        try {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = FileInputStream(config.certPath).use { cf.generateCertificates(it) }
            
            val lines = Files.readAllLines(Paths.get(config.keyPath), Charsets.UTF_8)
            val base64Content = lines.filter { !it.startsWith("-----") }.joinToString("")
            
            val decoded = Base64.getDecoder().decode(base64Content)
            
            // The key's algorithm is read off the certificate: the JDK does not parse it out of PKCS#8.
            val keyAlgorithm = certs.first().publicKey.algorithm
            val keySpec = if (lines.any { it.startsWith(ENCRYPTED_KEY_HEADER) }) {
                decryptKey(decoded, config)
            } else {
                PKCS8EncodedKeySpec(decoded)
            }
            val kf = KeyFactory.getInstance(keyAlgorithm)
            val privateKey = kf.generatePrivate(keySpec)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)

            // Never persisted or shared, so its password protects nothing.
            val keyStorePassword = CharArray(0)
            keyStore.setKeyEntry("client", privateKey, keyStorePassword, certs.toTypedArray())

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keyStorePassword)
            return kmf.keyManagers
        } catch (e: InitializationException) {
            // A missing password for an encrypted key, which says what is wrong already.
            throw e
        } catch (e: Exception) {
            // Every failure here looks the same from outside; the cause tells them apart.
            throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "Failed to load the client certificate '${config.certPath}' or its private key " +
                    "'${config.keyPath}'. The key must be a PKCS#8 key in PEM form, and must " +
                    "be the one that goes with the certificate.",
                e
            )
        }
    }

    /** What a PEM file whose key is encrypted opens on, and an unencrypted one never does. */
    private const val ENCRYPTED_KEY_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY"

    /** Unlocks an encrypted PKCS#8 key with [SslConfiguration.keyPassword], under the cipher the file names. */
    private fun decryptKey(encoded: ByteArray, config: SslConfiguration): PKCS8EncodedKeySpec {
        val password = config.keyPassword
            ?: throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "The client private key '${config.keyPath}' is encrypted, but no sslpassword was given to " +
                    "decrypt it."
            )

        val encryptedKey = EncryptedPrivateKeyInfo(encoded)
        val secretKeyFactory = SecretKeyFactory.getInstance(encryptedKey.algName)
        val pbeKey = secretKeyFactory.generateSecret(PBEKeySpec(password.toCharArray()))
        return encryptedKey.getKeySpec(pbeKey)
    }
}
