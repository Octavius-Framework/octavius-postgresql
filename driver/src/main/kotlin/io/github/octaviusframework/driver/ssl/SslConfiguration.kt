package io.github.octaviusframework.driver.ssl

/**
 * The TLS settings of a connection. A path left unset falls back to the JVM's own.
 *
 * @property rootCertPath The CA certificate the server is verified against under [SslMode.VERIFY_CA] and above.
 * @property certPath The client certificate, presented only together with [keyPath].
 * @property keyPath The private key of [certPath]: PKCS#8 in PEM form, RSA or EC, encrypted or not.
 * @property keyPassword Decrypts [keyPath]; an encrypted key without it raises `InitializationException(SSL_ERROR)`.
 */
data class SslConfiguration(
    val mode: SslMode,
    val rootCertPath: String? = null,
    val certPath: String? = null,
    val keyPath: String? = null,
    val keyPassword: String? = null
)
