package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `should throw NetworkException when backend connection is abruptly terminated`() {
        openSession().use { session1 ->
            openSession().use { session2 ->
                val pid = session1.createNativeQuery("SELECT pg_backend_pid()").fetchFieldStrict<Int>()

                session2.createNativeQuery("SELECT pg_terminate_backend($pid)").fetchRowStrict()

                val exception = assertFailsWith<NetworkException> {
                    session1.createNativeQuery("SELECT 1").fetchRowStrict()
                }
                logger.error(exception) {"" }
                assertTrue(
                    exception.reason == NetworkExceptionReason.CONNECTION_CLOSED_BY_PEER ||
                    exception.reason == NetworkExceptionReason.CONNECTION_ERROR,
                    "Expectiong CONNECTION_CLOSED_BY_PEER or CONNECTION_ERROR, got: ${exception.reason}"
                )
            }
        }
    }
}
