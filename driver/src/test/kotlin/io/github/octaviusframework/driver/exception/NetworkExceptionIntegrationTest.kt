package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A backend the server terminates is not here: the server says why before it closes, and that reaches the
 * caller as [SessionTerminatedException] - see `SessionTerminatedExceptionIntegrationTest`. A connection that
 * closes without a word is covered by `SessionTerminationProtocolTest`, which can close one that way on demand.
 */
class NetworkExceptionIntegrationTest : AbstractIntegrationTest() {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `should throw NetworkException with CONNECTION_TIMEOUT when socket timeout is reached`() {
        openSession { socketTimeout = 1 }.use { session -> // A one-second socket timeout
            val exception = assertFailsWith<NetworkException> {
                session.createNativeQuery("SELECT pg_sleep(2)").fetchRows()
            }
            logger.error(exception) {"" }
            assertEquals(NetworkExceptionReason.CONNECTION_TIMEOUT, exception.reason)
        }
    }
}
