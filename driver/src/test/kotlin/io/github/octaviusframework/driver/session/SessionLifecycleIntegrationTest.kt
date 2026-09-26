package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.ExecutionAbortedExceptionReason
import io.github.octaviusframework.driver.exception.ExecutionAbortedException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionLifecycleIntegrationTest : AbstractIntegrationTest() {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    @Test
    fun `should cancel long running query`() {
        val session = openSession()

        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Thread.sleep(100) // wait for query to start
            session.cancelQuery()
        }

        val exception = assertFailsWith<ExecutionAbortedException> {
            session.createNativeQuery("SELECT pg_sleep(2)").fetchRowStrict()
        }
        
        // 57014 is query_canceled
        assertEquals("57014", exception.sqlState)
        assertEquals(ExecutionAbortedExceptionReason.QUERY_CANCELED, exception.reason)
        logger.error(exception) { "" }
        // Session should be usable after query cancellation
        val result = session.createNativeQuery("SELECT 1").fetchRowStrict().get<Int>(0)
        assertEquals(1, result)

        session.close()
        executor.shutdown()
    }

    @Test
    fun `should abort session`() {
        val session = openSession()

        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Thread.sleep(100)
            session.abort()
        }

        val exception = assertFailsWith<NetworkException> {
            session.createNativeQuery("SELECT pg_sleep(2)").fetchRowStrict()
        }

        // Following queries should fail with CONNECTION_CLOSED since the stream is broken/aborted
        val nextException = assertFailsWith<NetworkException> {
            session.createNativeQuery("SELECT 1").fetchRowStrict()
        }

        assertEquals(NetworkExceptionReason.CONNECTION_CLOSED, nextException.reason)

        executor.shutdown()
    }
}
