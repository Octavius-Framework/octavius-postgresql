package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How `isValid` behaves against an exchange that is already in flight, from either direction.
 *
 * From another thread it must not interfere: it swaps the connection-wide socket timeout while it
 * runs, and doing that outside the lock that serializes exchanges would shorten the deadline of a
 * running query, surfacing as a read timeout - a `NetworkException` latching `isBroken` - on a
 * healthy connection. From the *same* thread it must not lie: reaching it from inside a streaming
 * block is a caller bug, and the reason enum saying so is worth more than a bare `false`.
 */
class IsValidConcurrencyTest : AbstractIntegrationTest() {

    @Test
    fun `isValid must not shorten the deadline of a query running on another thread`() {
        openSession().use { session ->
            session.networkTimeout = 0 // no limit - the query below must be allowed to take its time

            val queryStarted = CountDownLatch(1)
            val queryFailure = AtomicReference<Throwable?>()
            val queryResult = AtomicReference<Int?>()

            val queryThread = Thread {
                try {
                    // Streamed in batches on purpose: a single-shot query performs one read that is
                    // already blocked before isValid runs, and a timeout change does not reach it.
                    // Each batch here costs ~1.6s, comfortably over the one second isValid asks for,
                    // so any batch read issued after isValid meddled would time out.
                    var seen = 0
                    queryStarted.countDown()
                    session.createNativeQuery("SELECT i, pg_sleep(0.4) FROM generate_series(1, 12) i")
                        .forEachRow(fetchSize = 4) { seen++ }
                    queryResult.set(seen)
                } catch (t: Throwable) {
                    queryFailure.set(t)
                }
            }

            queryThread.start()
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS), "query thread never started")
            Thread.sleep(300) // let the query actually reach the socket read

            val valid = session.isValid(1)

            assertTrue(queryThread.join(Duration.ofSeconds(30)), "query thread did not finish")

            val thrown = queryFailure.get()
            assertTrue(
                thrown !is NetworkException,
                "the in-flight query was killed by a read timeout borrowed from isValid: $thrown"
            )
            assertNull(thrown, "the in-flight query was disturbed by isValid: $thrown")
            assertEquals(12, queryResult.get(), "the in-flight query did not complete normally")
            assertTrue(valid, "isValid should report the connection as usable")

            // The connection is still usable afterwards, with its own timeout restored.
            assertEquals(0, session.networkTimeout)
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
        }
    }

    @Test
    fun `isValid called from inside a streaming block reports the misuse instead of returning false`() {
        openSession().use { session ->
            var thrown: InvalidOperationException? = null

            session.createNativeQuery("SELECT i FROM generate_series(1, 3) i").forEachRow(fetchSize = 2) {
                if (thrown == null) {
                    thrown = assertFailsWith<InvalidOperationException> { session.isValid(1) }
                }
            }

            // The reason is the whole point: it names the mistake, where `false` would have hidden it.
            assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, thrown?.reason)

            // And the connection was never unhealthy - it was only busy.
            assertTrue(session.isValid(1), "the connection should be valid once the stream is done")
            assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())
        }
    }
}
