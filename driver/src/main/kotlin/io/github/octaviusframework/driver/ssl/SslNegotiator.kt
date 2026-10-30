package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
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

/**
 * Handles PostgreSQL SSL negotiation protocol and socket upgrade.
 */
internal object SslNegotiator {

    /**
     * Builds the effective SSL configuration for a connection.
     *
     * Kept separate from [negotiate] because the configuration outlives the handshake: a cancel
     * request travels on a second connection of its own and has to be given the same treatment.
     */
    fun configurationOf(properties: OctaviusProperties): SslConfiguration = SslConfiguration(
        mode = properties.sslMode ?: if (properties.ssl == true) SslMode.REQUIRE else SslMode.PREFER,
        rootCertPath = properties.sslRootCert,
        certPath = properties.sslCert,
        keyPath = properties.sslKey,
        keyPassword = properties.sslPassword
    )

    /**
     * Asks the server for TLS and upgrades the connection if it agrees, before the startup message and so
     * before any credential is on the wire.
     *
     * What a refusal means is [SslMode][io.github.octaviusframework.driver.properties.SslMode]'s to say:
     * under `DISABLE` nothing is asked at all, under the permissive modes a refusal continues in plaintext,
     * and under `REQUIRE` and stronger it is a failure.
     *
     * @param stream The connection to upgrade in place.
     * @param host The host, for hostname verification under the verifying modes.
     * @param port The port, used when reporting a failure.
     * @param config What was asked for: mode, certificates and key.
     * @throws InitializationException `SSL_ERROR` if the server refuses under a mode that requires TLS, or
     *   answers the request with something that is not a yes or a no.
     */
    fun negotiate(stream: PgStream, host: String, port: Int, config: SslConfiguration) {
        if (config.mode == SslMode.DISABLE) return

        stream.sendMessage(SSLRequestMessage())
        stream.flush()

        when (val response = stream.inputStream.readByte().toInt().toChar()) {
            'S' -> stream.upgradeToSSL(host, port, config)
            'N' -> {
                if (config.mode != SslMode.PREFER) {
                    stream.close()
                    throw InitializationException(InitializationExceptionReason.SSL_ERROR, "Server does not support SSL, but sslmode=${config.mode.value} was specified.")
                }
                // The connection line the factory writes already carries
                // both the outcome and the mode that allowed it. This stays for the connections
                // that never get such a line - a cancel request opens one of its own.
                logger.trace { "Server declined SSL; continuing in plaintext under sslmode=prefer" }
            }
            else -> {
                stream.close()
                throw InitializationException(InitializationExceptionReason.SSL_ERROR, "Unexpected SSL negotiation response: $response")
            }
        }
    }

    /**
     * Wraps an agreed socket in TLS and completes the handshake.
     *
     * The half of [negotiate] that runs once the server has said yes, kept separate because the asking and
     * the upgrading happen at different layers — the stream owns the socket and calls this, having been told
     * by [negotiate] that the server agreed.
     *
     * @param socket The plaintext socket the server has agreed to upgrade.
     * @param host The host to verify the certificate against.
     * @param port The port, used when reporting a failure.
     * @param config Mode, trust material and client certificate.
     * @return The negotiated socket.
     * @throws InitializationException `SSL_ERROR` if the handshake or verification fails.
     */
    fun upgrade(socket: Socket, host: String, port: Int, config: SslConfiguration): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")

        val trustManagers = createTrustManagers(config)
        val keyManagers = createKeyManagers(config)
        
        sslContext.init(keyManagers, trustManagers, SecureRandom())
        
        val factory = sslContext.socketFactory
        val sslSocket = factory.createSocket(socket, host, port, true) as SSLSocket
        
        if (config.mode == SslMode.VERIFY_FULL) {
            val sslParams = sslContext.defaultSSLParameters
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = sslParams
        }
        
        sslSocket.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3").filter { it in sslSocket.supportedProtocols }.toTypedArray()
        
        try {
            sslSocket.startHandshake()
        } catch (e: SSLException) {
            // A certificate the verifying modes refuse - an issuer the trust store does not know, a host name it
            // does not carry - is the TLS library's exception up to here, and SSL_ERROR is what the caller is told
            // to expect. A timeout is not an SSLException, so it still reaches the factory as one.
            throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "TLS handshake with $host:$port failed under sslmode=${config.mode.value}: ${e.message}",
                e
            )
        }
        // Same reasoning as the plaintext case: that the connection is encrypted is on the
        // connection line already, and only the protocol and cipher are new here.
        logger.trace {
            "TLS established with $host:$port under sslmode=${config.mode.value} " +
                "(${sslSocket.session.protocol}, ${sslSocket.session.cipherSuite})"
        }
        return sslSocket
    }

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
            
            // The certificate names the algorithm its key was made for, and a private key that did
            // not match it would be useless here anyway - so it is read off the certificate rather
            // than assumed or guessed at. PKCS#8 carries the algorithm's OID, but nothing in the
            // JDK will hand it over: `PKCS8EncodedKeySpec` stores the bytes without parsing them.
            // This is how pgjdbc resolves it too, and it is what lets an EC key work without the
            // driver keeping a list of the algorithms it has heard of.
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

            // The keystore is built here, read once by the factory below and dropped; nothing
            // persists it and nothing else can reach it, so there is nothing for a password to
            // protect. An empty one is what it has always been given when `sslpassword` was unset,
            // and now that the property has a real job - the key file - it is what it is given
            // always.
            val keyStorePassword = CharArray(0)
            keyStore.setKeyEntry("client", privateKey, keyStorePassword, certs.toTypedArray())

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, keyStorePassword)
            return kmf.keyManagers
        } catch (e: InitializationException) {
            // Already says exactly what is wrong - a missing password for an encrypted key. Restating
            // it below would bury that under the generic advice.
            throw e
        } catch (e: Exception) {
            // Naming both files and leaving the cause attached, because everything that can go
            // wrong here goes wrong the same way from the outside - the key is encrypted, or is
            // PKCS#1 rather than PKCS#8, or belongs to a different certificate - and only the
            // cause tells them apart.
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

    /**
     * Unlocks an encrypted PKCS#8 key with [SslConfiguration.keyPassword].
     *
     * Which cipher it was locked with is not asked for and not configurable: the file states it, and
     * [EncryptedPrivateKeyInfo] resolves the statement - including a PBES2 parameter block, which it
     * reports already reduced to the concrete algorithm the JVM knows by name. What OpenSSL writes
     * today and what it wrote a decade ago both come back as something `SecretKeyFactory` accepts.
     */
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
