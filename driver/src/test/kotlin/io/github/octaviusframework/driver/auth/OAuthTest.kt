package io.github.octaviusframework.driver.auth

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.ssl.SslMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Names the user it is asked for, which the test validator reads back as the identity. */
object LegatusTokenProvider : OAuthTokenProvider {
    override fun token(request: OAuthTokenRequest): String = request.user
}

/**
 * Against the server `scripts/oauth-test-server.ps1` stands up. Its validator takes a token to name the role it
 * logs in as, so `legatus` logs in only with the token `legatus`, and a login that goes through shows the token
 * reached the server as the provider gave it.
 */
@EnabledIfEnvironmentVariable(named = "TEST_OAUTH", matches = "true")
class OAuthTest {

    private val url = "jdbc:octavius://localhost:5438/octavius_test"

    private fun legatus(block: OctaviusProperties.() -> Unit) = OctaviusProperties().apply {
        user = "legatus"
        sslMode = SslMode.REQUIRE
        block()
    }

    @Test
    fun `logs in with the token the provider gives`() {
        getOctaviusSession(url, legatus { oauthTokenProvider = OAuthTokenProvider { "legatus" } }).use { session ->
            assertEquals("legatus", session.createNativeQuery("SELECT current_user").fetchFieldStrict<String>())
        }
    }

    @Test
    fun `asks the provider with the server, user and database of the login`() {
        var asked: OAuthTokenRequest? = null
        getOctaviusSession(url, legatus {
            oauthTokenProvider = OAuthTokenProvider { asked = it; "legatus" }
        }).close()

        val request = asked!!
        assertEquals("localhost", request.host)
        assertEquals(5438, request.port)
        assertEquals("legatus", request.user)
        assertEquals("octavius_test", request.database)
    }

    @Test
    fun `a token the server does not accept is rejected as credentials`() {
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus { oauthTokenProvider = OAuthTokenProvider { "exsul" } })
        }

        assertEquals(InitializationExceptionReason.SERVER_REJECTED_CREDENTIALS, ex.reason)
        assertEquals("28000", ex.sqlState)
    }

    @Test
    fun `loads a provider named by class`() {
        val session = getOctaviusSession(
            "$url?oauthTokenProviderClassName=${LegatusTokenProvider::class.java.name}",
            legatus { }
        )
        session.close()
    }

    @Test
    fun `no token is asked for over an unencrypted connection`() {
        var asked = false
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus {
                sslMode = SslMode.DISABLE
                oauthTokenProvider = OAuthTokenProvider { asked = true; "legatus" }
            })
        }

        assertEquals(InitializationExceptionReason.WITHHELD_CREDENTIALS, ex.reason)
        assertTrue(ex.details!!.contains("not encrypted"), ex.details)
        assertFalse(asked, "The provider was asked for a token the driver would not send")
    }

    @Test
    fun `no token is asked for under channelBinding=require`() {
        var asked = false
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus {
                channelBinding = ChannelBinding.REQUIRE
                oauthTokenProvider = OAuthTokenProvider { asked = true; "legatus" }
            })
        }

        assertEquals(InitializationExceptionReason.UNSUPPORTED_MECHANISM, ex.reason)
        assertTrue(ex.details!!.contains("channelBinding=require"), ex.details)
        assertFalse(asked, "The provider was asked for a token the driver would not send")
    }

    @Test
    fun `a server asking for a token with no provider configured is refused`() {
        val ex = assertThrows<InitializationException> { getOctaviusSession(url, legatus { }) }

        assertEquals(InitializationExceptionReason.WITHHELD_CREDENTIALS, ex.reason)
        assertTrue(ex.details!!.contains("oauthTokenProvider"), ex.details)
    }

    @Test
    fun `a provider that throws fails the login with what it threw as the cause`() {
        val failure = IllegalStateException("The issuer did not answer")
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus { oauthTokenProvider = OAuthTokenProvider { throw failure } })
        }

        assertEquals(InitializationExceptionReason.TOKEN_PROVIDER_FAILED, ex.reason)
        assertSame(failure, ex.cause)
    }

    @Test
    fun `a token with its Bearer scheme in front is refused before it is sent`() {
        // Sent, the server would take "Bearer Bearer legatus" for a malformed token and refuse the login as
        // credentials; refused here, it is TOKEN_PROVIDER_FAILED.
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus { oauthTokenProvider = OAuthTokenProvider { "Bearer legatus" } })
        }

        assertEquals(InitializationExceptionReason.TOKEN_PROVIDER_FAILED, ex.reason)
        assertTrue(ex.details!!.contains("'Bearer '"), ex.details)
    }

    @Test
    fun `a token that is not a bearer token is refused before it is sent, and not repeated`() {
        // A separator would end the auth value and start a key-value pair of the token's choosing.
        val token = "legatus\u0001auth=Bearer exsul"
        val ex = assertThrows<InitializationException> {
            getOctaviusSession(url, legatus { oauthTokenProvider = OAuthTokenProvider { token } })
        }

        assertEquals(InitializationExceptionReason.TOKEN_PROVIDER_FAILED, ex.reason)
        assertFalse(ex.details!!.contains("exsul"), ex.details)
    }

    @Test
    fun `a provider class that cannot be loaded fails a login that asks for no token too`() {
        val ex = assertThrows<InitializationException> {
            getOctaviusSession("$url?oauthTokenProviderClassName=com.example.MissingProvider", OctaviusProperties().apply {
                user = "postgres"
                password = "1234"
            })
        }

        assertEquals(InitializationExceptionReason.TOKEN_PROVIDER_FAILED, ex.reason)
        assertTrue(ex.details!!.contains("com.example.MissingProvider"), ex.details)
    }
}
