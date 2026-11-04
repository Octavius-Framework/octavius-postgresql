package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.SessionTerminatedExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@EnabledIfEnvironmentVariable(named = "TEST_SSL", matches = "true")
class SslIntegrationTest {

    private val url = "jdbc:octavius://localhost:5433/octavius_test"

    @Test
    fun testSslModeRequire() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.REQUIRE
        }

        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with REQUIRE")
        } finally {
            session.close()
        }
    }

    @Test
    fun testNegotiatedProtocolIsTls13() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.REQUIRE
        }

        val session = getOctaviusSession(url, properties)

        try {
            // The driver enables TLS 1.2 and 1.3 and lets the server pick, so against a server that
            // offers both - PostgreSQL 18 does, out of the box - the answer has to be 1.3. Asserting
            // "one of the two" would pass with the driver pinned to 1.2, which is exactly the failure
            // this exists to catch: an SSLContext asked for by version caps the handshake at that
            // version whatever enabledProtocols says afterwards, and nothing else reports it.
            val version = session.createNativeQuery("SELECT version FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                .fetchFieldStrict<String>()
            assertEquals("TLSv1.3", version, "Server and driver both offer TLS 1.3; the handshake should land on it")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslModePrefer() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.PREFER
        }

        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with PREFER (server supports it)")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslModeDisable() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = false
            sslMode = SslMode.DISABLE
        }

        val session = getOctaviusSession(url, properties)

        try {
            // Depending on the server configuration, it might reject non-SSL connections, but default postgres docker image allows both.
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(!isSsl, "Connection should NOT be SSL encrypted with DISABLE")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslModeVerifyCaFailsWithoutCert() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            // Not providing trust store or CA certs on purpose
        }

        assertThrows<Exception>("Should throw because we haven't configured a valid CA certificate") {
            getOctaviusSession(url, properties)
        }
    }

    @Test
    fun testSslModeVerifyCaWithCert() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        assumeTrue(rootCert != null, "No root cert provided.")

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            sslRootCert = rootCert
        }

        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with VERIFY_CA")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslModeVerifyFullWithCert() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        assumeTrue(rootCert != null, "No root cert provided.")

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_FULL
            sslRootCert = rootCert
        }

        // Host must match the certificate's CN (localhost)
        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with VERIFY_FULL")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslModeVerifyFullRefusesAnotherHostName() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        assumeTrue(rootCert != null, "No root cert provided.")

        fun properties(mode: SslMode) = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = mode
            sslRootCert = rootCert
        }

        // The certificate names localhost and nothing else, so 127.0.0.1 is the same server under a name it does
        // not carry. verify-ca checks only who signed it and lets the connection through; verify-full checks the
        // name as well.
        val byAddress = "jdbc:octavius://127.0.0.1:5433/octavius_test"
        getOctaviusSession(byAddress, properties(SslMode.VERIFY_CA)).close()

        val ex = assertThrows<InitializationException> {
            getOctaviusSession(byAddress, properties(SslMode.VERIFY_FULL))
        }
        assertEquals(InitializationExceptionReason.SSL_ERROR, ex.reason)
    }

    @Test
    fun testSslClientAuth() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        val clientCert = System.getenv("SSL_CERT")
        val clientKey = System.getenv("SSL_KEY")
        assumeTrue(rootCert != null && clientCert != null && clientKey != null, "No client certs provided.")

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            sslRootCert = rootCert
            sslCert = clientCert
            sslKey = clientKey
        }

        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with client certificates")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslClientAuthWithEcKey() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        val clientCert = System.getenv("SSL_CERT_EC")
        val clientKey = System.getenv("SSL_KEY_EC")
        assumeTrue(rootCert != null && clientCert != null && clientKey != null, "No EC client certs provided.")

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            sslRootCert = rootCert
            sslCert = clientCert
            sslKey = clientKey
        }

        // Same exchange as testSslClientAuth, on a key that is not RSA. The driver takes the key's
        // algorithm from the certificate presented alongside it, so this passes for any algorithm
        // the JVM has a KeyFactory for; asking for "RSA" outright failed here with
        // InvalidKeySpecException before the certificate was consulted.
        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with an EC client certificate")
        } finally {
            session.close()
        }
    }

    @Test
    fun testSslClientAuthWithEncryptedKey() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        val clientCert = System.getenv("SSL_CERT")
        val clientKey = System.getenv("SSL_KEY_ENCRYPTED")
        val keyPassword = System.getenv("SSL_KEY_PASSWORD")
        assumeTrue(
            rootCert != null && clientCert != null && clientKey != null && keyPassword != null,
            "No encrypted client key provided."
        )

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            sslRootCert = rootCert
            sslCert = clientCert
            sslKey = clientKey
            sslPassword = keyPassword
        }

        // The same certificate as testSslClientAuth, presented with the same key locked behind a
        // password - so anything this proves is about the decryption and nothing else.
        val session = getOctaviusSession(url, properties)

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted with an encrypted client key")
        } finally {
            session.close()
        }
    }

    @Test
    fun testEncryptedKeyWithoutPasswordIsRefused() {
        val rootCert = System.getenv("SSL_ROOT_CERT")
        val clientCert = System.getenv("SSL_CERT")
        val clientKey = System.getenv("SSL_KEY_ENCRYPTED")
        assumeTrue(rootCert != null && clientCert != null && clientKey != null, "No encrypted client key provided.")

        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.VERIFY_CA
            sslRootCert = rootCert
            sslCert = clientCert
            sslKey = clientKey
            // sslPassword deliberately left unset
        }

        val exception = assertFailsWith<InitializationException> { getOctaviusSession(url, properties) }

        assertEquals(InitializationExceptionReason.SSL_ERROR, exception.reason)
        // The message has to name the missing property, because nothing else about the failure
        // points at it: the key file is present, readable and perfectly valid.
        assertTrue(
            exception.details!!.contains("sslpassword"),
            "The failure should name the property that is missing, was: ${exception.details}"
        )
    }

    @Test
    fun testCleartextPasswordOverTls() {
        val properties = OctaviusProperties().apply {
            user = "cleartext_user"
            password = "senatus"
            ssl = true
            sslMode = SslMode.REQUIRE
        }

        // `password` in pg_hba is what ldap, pam and radius all reduce to on the wire, so this is
        // the exchange those deployments need and the only one the driver sends a bare password on.
        val session = getOctaviusSession(url, properties)

        try {
            val who = session.createNativeQuery("SELECT current_user").fetchFieldStrict<String>()
            assertEquals("cleartext_user", who)
        } finally {
            session.close()
        }
    }

    @Test
    fun testCleartextPasswordRefusedWithoutTls() {
        val properties = OctaviusProperties().apply {
            user = "cleartext_user"
            password = "senatus"
            ssl = false
            sslMode = SslMode.DISABLE
        }

        // The server offers the same exchange over a plaintext connection - there is a `host` rule
        // for this role as well - and the driver is what refuses, not the server.
        val exception = assertFailsWith<InitializationException> { getOctaviusSession(url, properties) }

        assertEquals(InitializationExceptionReason.UNSUPPORTED_PASSWORD_ENCRYPTION, exception.reason)
        assertTrue(
            exception.details!!.contains("sslmode"),
            "The failure should say what to raise, was: ${exception.details}"
        )
    }

    @Test
    fun testCleartextPasswordRefusedUnderChannelBindingRequire() {
        val properties = OctaviusProperties().apply {
            user = "cleartext_user"
            password = "senatus"
            ssl = true
            sslMode = SslMode.REQUIRE
            channelBinding = ChannelBinding.REQUIRE
        }

        // Encrypted, so the password could have gone out - but a cleartext exchange can never be
        // bound to the channel, so the login is already lost and the credential stays home.
        val exception = assertFailsWith<InitializationException> { getOctaviusSession(url, properties) }

        assertEquals(InitializationExceptionReason.UNSUPPORTED_MECHANISM, exception.reason)
    }

    @Test
    fun testCancelQueryOverSsl() {
        val properties = OctaviusProperties().apply {
            user = "postgres"
            password = "1234"
            ssl = true
            sslMode = SslMode.REQUIRE
        }

        val session = getOctaviusSession(url, properties)
        val executor = Executors.newSingleThreadExecutor()

        try {
            // A cancel request cannot travel on the session's own connection, so it opens a second
            // one - which has to negotiate TLS for itself before the request can land. If it did
            // not, the request would never reach the server under REQUIRE and pg_sleep would run
            // to completion instead of being aborted.
            executor.submit {
                Thread.sleep(200)
                session.cancelQuery()
            }

            val exception = assertFailsWith<ExecutionAbortedException> {
                session.createNativeQuery("SELECT pg_sleep(5)").fetchRowStrict()
            }
            assertEquals("57014", exception.sqlState)

            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Session should survive the cancellation, still encrypted")
        } finally {
            executor.shutdown()
            session.close()
        }
    }

    private fun requireTls() = OctaviusProperties().apply {
        user = "postgres"
        password = "1234"
        ssl = true
        sslMode = SslMode.REQUIRE
    }

    @Test
    fun testSessionTerminatedWhileIdleOverSsl() {
        getOctaviusSession(url, requireTls()).use { session ->
            val pid = session.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()

            getOctaviusSession(url, requireTls()).use { admin ->
                admin.createNativeQuery("SELECT pg_terminate_backend($1)").fetchFieldStrict<Boolean>(pid)
                val deadline = System.nanoTime() + 10_000_000_000L
                while (admin.createNativeQuery("SELECT count(*) FROM pg_stat_activity WHERE pid = $1").fetchFieldStrict<Long>(pid) > 0) {
                    assertTrue(System.nanoTime() < deadline, "backend $pid never went away")
                    Thread.sleep(20)
                }
            }

            // The FATAL is still encrypted on the socket, which the look before the exchange cannot see into,
            // so it is read after the write instead - and reported as itself, not as the reset behind it.
            val exception = assertFailsWith<SessionTerminatedException> {
                session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>()
            }
            assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, exception.reason)
        }
    }

    @Test
    fun testTransactionTimeoutOverSsl() {
        getOctaviusSession(url, requireTls()).use { session ->
            val exception = assertFailsWith<SessionTerminatedException> {
                session.transaction.required(transactionTimeout = 200.milliseconds) {
                    createNativeQuery("SELECT pg_sleep(5)").fetchRows()
                }
            }
            assertEquals(SessionTerminatedExceptionReason.TRANSACTION_TIMEOUT, exception.reason)
        }
    }
}
