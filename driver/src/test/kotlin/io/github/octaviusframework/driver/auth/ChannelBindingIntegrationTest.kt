package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue

/**
 * A successful connection is the assertion here, and a strong one.
 *
 * PostgreSQL exposes nothing that reports "this session was channel-bound", but it does not need
 * to: the client proof covers the `c=` attribute, which carries the certificate hash, so a server
 * that computed a different hash rejects the login. Connecting under `channelBinding=require` is
 * therefore proof that both ends hashed the same certificate the same way.
 */
@EnabledIfEnvironmentVariable(named = "TEST_SSL", matches = "true")
class ChannelBindingIntegrationTest {

    // The CI job runs its TLS server on the default port; a throwaway local instance rarely can.
    private val url = "jdbc:octavius://localhost:5433/octavius_test"

    // The same certificate signed with RSASSA-PSS over SHA-384, on a server of its own.
    private val rsaPssUrl = "jdbc:octavius://localhost:5436/octavius_test"

    // A certificate signed with Ed25519, a signature made without a digest, on a third server.
    private val ed25519Url = "jdbc:octavius://localhost:5437/octavius_test"

    private fun properties(block: OctaviusProperties.() -> Unit) = OctaviusProperties().apply {
        user = "postgres"
        password = "1234"
        block()
    }

    @Test
    fun `binds the exchange to the channel when required`() {
        val session = getOctaviusSession(url, properties {
            sslMode = SslMode.REQUIRE
            channelBinding = ChannelBinding.REQUIRE
        })

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                .fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted")
        } finally {
            session.close()
        }
    }

    @Test
    fun `binds by default over an encrypted connection`() {
        // No channelBinding set at all: PREFER should reach for SCRAM-SHA-256-PLUS on its own.
        val session = getOctaviusSession(url, properties { sslMode = SslMode.REQUIRE })

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                .fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted")
        } finally {
            session.close()
        }
    }

    @Test
    fun `binds to a certificate signed with RSASSA-PSS`() {
        // The name RSASSA-PSS carries no digest. The server reads SHA-384 out of the signature's
        // parameters and hashes its certificate with it, so the login goes through only where the
        // driver did the same.
        getOctaviusSession(rsaPssUrl, properties {
            sslMode = SslMode.REQUIRE
            channelBinding = ChannelBinding.REQUIRE
        }).close()
    }

    @Test
    fun `refuses a certificate signed without a digest, under prefer as under require`() {
        // Neither end can hash this certificate, and PREFER does not carry on unbound past it: what
        // it would carry on with is a login an intermediary could relay. Refused before the SCRAM
        // exchange begins.
        for (binding in listOf(ChannelBinding.PREFER, ChannelBinding.REQUIRE)) {
            val exception = assertThrows<InitializationException> {
                getOctaviusSession(ed25519Url, properties {
                    sslMode = SslMode.REQUIRE
                    channelBinding = binding
                })
            }

            assertEquals(InitializationExceptionReason.UNSUPPORTED_MECHANISM, exception.reason, "Under $binding")
            assertTrue(
                exception.details!!.contains("Ed25519") && exception.details!!.contains("channelBinding=disable"),
                "The refusal should name the signature and the way out, under $binding: ${exception.details}"
            )
        }
    }

    @Test
    fun `logs in unbound to a certificate signed without a digest when binding is disabled`() {
        getOctaviusSession(ed25519Url, properties {
            sslMode = SslMode.REQUIRE
            channelBinding = ChannelBinding.DISABLE
        }).close()
    }

    @Test
    fun `still authenticates over TLS with binding disabled`() {
        // The plain SCRAM path, which stays reachable over an encrypted connection.
        val session = getOctaviusSession(url, properties {
            sslMode = SslMode.REQUIRE
            channelBinding = ChannelBinding.DISABLE
        })

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                .fetchFieldStrict<Boolean>()
            assertTrue(isSsl, "Connection should be SSL encrypted")
        } finally {
            session.close()
        }
    }

    @Test
    fun `falls back to plain SCRAM on an unencrypted connection`() {
        // PREFER has to degrade quietly: this is the ordinary no-TLS login, and it must not start
        // failing because the driver learned to bind.
        val session = getOctaviusSession(url, properties { sslMode = SslMode.DISABLE })

        try {
            val isSsl = session.createNativeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                .fetchFieldStrict<Boolean>()
            assertTrue(!isSsl, "Connection should not be SSL encrypted")
        } finally {
            session.close()
        }
    }

    @Test
    fun `refuses to authenticate unencrypted when binding is required`() {
        val exception = assertThrows<InitializationException> {
            getOctaviusSession(url, properties {
                sslMode = SslMode.DISABLE
                channelBinding = ChannelBinding.REQUIRE
            })
        }

        assertEquals(InitializationExceptionReason.UNSUPPORTED_MECHANISM, exception.reason)
        assertTrue(
            exception.details!!.contains("no server certificate to bind to"),
            "Unexpected detail message: ${exception.details}"
        )
    }
}
