package io.github.octaviusframework.driver.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*

/**
 * Checks the `tls-server-end-point` hash against OpenSSL rather than against itself.
 *
 * Every expected value below came out of:
 *
 * ```
 * openssl x509 -in cert-sha256.pem -outform DER | openssl dgst -sha256 -binary | openssl base64
 * ```
 *
 * which is the whole point of the exercise: OpenSSL is the implementation sitting on the other
 * side of a real exchange, and a hash the two sides disagree on is an authentication failure with
 * nothing in the message to explain it.
 */
class TlsServerEndPointTest {

    private fun hashOf(fixture: String): String? {
        val certificate = javaClass.getResourceAsStream("/channelbinding/$fixture").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return TlsServerEndPoint.hash(certificate)?.let { Base64.getEncoder().encodeToString(it) }
    }

    @Test
    fun `hashes a SHA-256 signed certificate with SHA-256`() {
        assertEquals("VWQeR6oRgqHibz2BzADLIdeGhNiVU0W1MgFbfHcA7kI=", hashOf("cert-sha256.pem"))
    }

    @Test
    fun `follows a certificate up to SHA-384`() {
        assertEquals(
            "7cvrOQqIpK1ScAnsT+3P3MYkbYCUtGjBmBs06sV7PPCcX4wzfTnOSWtFRRFfs3/u",
            hashOf("cert-sha384.pem")
        )
    }

    @Test
    fun `promotes a SHA-1 signature to SHA-256`() {
        // RFC 5929 refuses to bind to a broken digest, so the same bytes are hashed with SHA-256
        // instead - which is why this differs from the SHA-1 of the certificate.
        assertEquals("X15PMexD/8hx+ptNQ0sEptqE7H3+cibkLUL4uDnI8b8=", hashOf("cert-sha1.pem"))
    }

    @Test
    fun `reads the digest of an RSASSA-PSS signature from its parameters`() {
        // The name is RSASSA-PSS whatever the digest. SHA-384 is in the parameters, and that is
        // where the server's OpenSSL reads it from.
        assertEquals(
            "+bFn/O05XsOY2qZaUl6oU4FAQfpCiVpqvb3/2t6O+E9XacTVGhtHztlnBMT4Zamq",
            hashOf("cert-rsapss-sha384.pem")
        )
    }

    @Test
    fun `promotes an RSASSA-PSS signature over SHA-1 to SHA-256`() {
        // Parameters left empty, which RFC 4055 reads as SHA-1 - and then the same promotion as for
        // a SHA-1 signature by name.
        assertEquals("XM2GiVjJ3Pc32fTyeR4bgil6m4S0HwlIIRBblKe4ke0=", hashOf("cert-rsapss-sha1.pem"))
    }

    @Test
    fun `follows a SHA-3 signature to its own digest`() {
        // SHA3-384withRSA hashes with SHA3-384, hyphen and all. No TLS signature scheme uses SHA-3,
        // so JSSE refuses such a certificate in the handshake and no login gets this far - but the
        // server binds to it with SHA3-384 when a client does.
        assertEquals(
            "B9OvAdDfHLzVwetqPnr00CV9uMRB/h8aJ0+VG1hJDCk6fVvVFGAIgMG/BHLWBUaz",
            hashOf("cert-sha3-384.pem")
        )
    }

    @Test
    fun `has no hash for a signature made without a digest`() {
        // Ed25519 and Ed448 sign the message itself rather than a digest of it, and RFC 5929
        // leaves the binding undefined there. The server has no digest to hash with either.
        assertNull(hashOf("cert-ed25519.pem"))
        assertNull(hashOf("cert-ed448.pem"))
    }
}
