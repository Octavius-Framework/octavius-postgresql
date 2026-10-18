package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import java.io.IOException
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.spec.PSSParameterSpec

/**
 * Computes the `tls-server-end-point` channel binding data of RFC 5929 - the hash of the server's
 * certificate that a `SCRAM-SHA-256-PLUS` exchange is bound to.
 *
 * PostgreSQL implements no other binding type, so this is the whole of the subject.
 */
internal object TlsServerEndPoint {

    /** `id-RSASSA-PSS`, the signature whose digest is in its parameters rather than in its name. */
    private const val RSASSA_PSS = "1.2.840.113549.1.1.10"

    /**
     * Hashes the DER encoding of [certificate] with the digest [digestFor] picks - the one the
     * server hashes its own certificate with, so that both ends arrive at the same bytes.
     *
     * @throws InitializationException if the certificate cannot be encoded or the digest is
     *   unavailable in this JVM - either way there is nothing to bind to.
     */
    fun hash(certificate: X509Certificate): ByteArray {
        val algorithm = digestFor(certificate)
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
     * The digest PostgreSQL hashes its certificate with.
     *
     * The server asks OpenSSL's `X509_get_signature_info` which digest the certificate was signed
     * with and uses that one, except that MD5 and SHA-1 become SHA-256: RFC 5929 will not bind to a
     * broken digest. The JDK names a signature after its digest - `SHA384withECDSA`,
     * `SHA3-256withRSA` - so the name says it, except for RSASSA-PSS. That name is the same whatever
     * the digest, which sits in the parameters beside it, and OpenSSL reads it from there.
     *
     * A name with no digest in it - Ed25519, Ed448 - gets SHA-256, which the server cannot match:
     * it has no digest for such a signature and fails the login itself.
     */
    private fun digestFor(certificate: X509Certificate): String {
        val signedWith = if (certificate.sigAlgOID == RSASSA_PSS) {
            pssDigest(certificate)
        } else {
            val name = certificate.sigAlgName
            val with = name.indexOf("with", ignoreCase = true)
            if (with > 0) name.substring(0, with) else null
        }
        return when (val digest = signedWith?.let(::standardName)) {
            null, "MD5", "SHA-1" -> "SHA-256"
            else -> digest
        }
    }

    /**
     * The digest an RSASSA-PSS signature was made with, from its parameters: SHA-1 where they leave
     * it out, as RFC 4055 has it, and null where there are none to read.
     */
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

    /**
     * `SHA384` as `SHA-384`, so that a digest goes by one name whether it came out of a signature's
     * name or its parameters. A SHA-3 name has its hyphen already, after the 3.
     */
    private fun standardName(digest: String): String {
        val name = digest.uppercase()
        return if (name.startsWith("SHA") && name.getOrNull(3)?.isDigit() == true && !name.startsWith("SHA3-")) {
            "SHA-" + name.substring(3)
        } else {
            name
        }
    }
}
