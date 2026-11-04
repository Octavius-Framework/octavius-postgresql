package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import java.io.IOException
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.PSSParameterSpec

/** The `tls-server-end-point` binding data of RFC 5929: the hash of the server's certificate. */
internal object TlsServerEndPoint {

    /** `id-RSASSA-PSS`, the signature whose digest is in its parameters rather than in its name. */
    private const val RSASSA_PSS = "1.2.840.113549.1.1.10"

    /**
     * [certificate] hashed with the digest the server uses, or `null` where its signature names none.
     *
     * @throws InitializationException if the certificate cannot be encoded or the digest is unavailable.
     */
    fun hash(certificate: X509Certificate): ByteArray? {
        val algorithm = digestFor(certificate) ?: return null
        return try {
            MessageDigest.getInstance(algorithm).digest(certificate.encoded)
        } catch (e: GeneralSecurityException) {
            throw InitializationException(
                InitializationExceptionReason.SSL_ERROR,
                "Could not hash the server certificate with $algorithm for channel binding. " +
                    "The certificate is signed with ${certificate.sigAlgName}.",
                e
            )
        }
    }

    /**
     * The digest the certificate was signed with, MD5 and SHA-1 raised to SHA-256, as the server picks it: from the
     * signature's name, or for RSASSA-PSS its parameters. `null` for Ed25519 and Ed448, which have none.
     */
    private fun digestFor(certificate: X509Certificate): String? {
        val signedWith = if (certificate.sigAlgOID == RSASSA_PSS) {
            pssDigest(certificate)
        } else {
            val name = certificate.sigAlgName
            val with = name.indexOf("with", ignoreCase = true)
            if (with > 0) name.substring(0, with) else null
        }
        return when (val digest = signedWith?.let(::standardName)) {
            "MD5", "SHA-1" -> "SHA-256"
            else -> digest
        }
    }

    /** An RSASSA-PSS signature's digest from its parameters: SHA-1 where they omit it, `null` without them. */
    private fun pssDigest(certificate: X509Certificate): String? {
        val parameters = certificate.sigAlgParams ?: return null
        return try {
            val decoded = AlgorithmParameters.getInstance("RSASSA-PSS").apply { init(parameters) }
            decoded.getParameterSpec(PSSParameterSpec::class.java).digestAlgorithm
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** `SHA384` as `SHA-384`, one name wherever the digest came from. */
    private fun standardName(digest: String): String {
        val name = digest.uppercase()
        return if (name.startsWith("SHA") && name.getOrNull(3)?.isDigit() == true && !name.startsWith("SHA3-")) {
            "SHA-" + name.substring(3)
        } else {
            name
        }
    }
}
