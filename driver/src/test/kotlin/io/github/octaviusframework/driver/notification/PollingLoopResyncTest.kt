package io.github.octaviusframework.driver.notification

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A polling listener loop times out on the socket on every idle tick. That must leave the read
 * buffer exactly as it was, so the connection is still in sync afterwards - a buffer rewound by
 * a failed fill would replay bytes already consumed and answer later queries with earlier results.
 */
class PollingLoopResyncTest : AbstractIntegrationTest() {

    @Test
    fun `connection stays in sync after an idle polling loop`() = runBlocking {
        val session = openSession()
        session.notifications.listen("watchtower")

        // A query before the loop, so there is a previous response available to be replayed
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchFieldStrict<Int>())

        val loop = launch { session.notifications.startPollingListenerLoop(100) }
        delay(600) // several idle ticks, each one a socket timeout
        loop.cancelAndJoin()

        // Each query must get its own answer, not the previous one
        assertEquals(7, session.createNativeQuery("SELECT 7").fetchFieldStrict<Int>())
        assertEquals(42, session.createNativeQuery("SELECT 42").fetchFieldStrict<Int>())
        assertEquals(3L, session.createNativeQuery("SELECT count(*) FROM generate_series(1, 3)").fetchFieldStrict<Long>())

        session.close()
    }

    @Test
    fun `repeated start and cancel leaves the session usable`() = runBlocking {
        val session = openSession()
        session.notifications.listen("signal_fire")

        repeat(3) { i ->
            val loop = launch { session.notifications.startPollingListenerLoop(100) }
            delay(300)
            loop.cancelAndJoin()
            assertEquals(i, session.createNativeQuery("SELECT $i").fetchFieldStrict<Int>())
        }

        session.close()
    }
}
