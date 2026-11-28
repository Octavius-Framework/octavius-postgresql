package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The cleartext password a `password`, `ldap`, `pam` or `radius` line of `pg_hba.conf` asks for, against the TLS
 * server's `cleartext_user`, which has a `host` rule as well as a `hostssl` one.
 */
@EnabledIfEnvironmentVariable(named = "TEST_SSL", matches = "true")
class CleartextPasswordTest {

    private val url = "jdbc:octavius://localhost:5433/octavius_test"

    @Test
    fun `a cleartext password goes out over TLS`() {
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
    fun `a cleartext password is refused without TLS, and the refusal says to raise sslmode`() {
        val properties = OctaviusProperties().apply {
            user = "cleartext_user"
            password = "senatus"
            ssl = false
            sslMode = SslMode.DISABLE
        }

        // The server offers the same exchange over a plaintext connection - there is a `host` rule
        // for this role as well - and the driver is what refuses, not the server.
        val exception = assertFailsWith<InitializationException> { getOctaviusSession(url, properties) }

        assertEquals(InitializationExceptionReason.WITHHELD_CREDENTIALS, exception.reason)
        assertTrue(
            exception.details!!.contains("sslmode"),
            "The failure should say what to raise, was: ${exception.details}"
        )
    }

    @Test
    fun `a cleartext password is refused under channelBinding=require`() {
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
}
