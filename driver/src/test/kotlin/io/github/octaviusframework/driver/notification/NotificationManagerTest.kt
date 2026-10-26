package io.github.octaviusframework.driver.notification

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.assumeUnreadFatalSurvives
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.exception.SessionTerminatedExceptionReason

class NotificationManagerTest : AbstractIntegrationTest() {

    @Test
    fun testPollingListener() = runBlocking {
        val listenerSession = openSession()
        val notifierSession = openSession()

        listenerSession.notifications.listen("couriers")

        val pollingJob = launch {
            listenerSession.notifications.startPollingListenerLoop(100)
        }

        val notificationDeferred = async {
            listenerSession.notifications.messages.first { it.channel == "couriers" }
        }

        // Allow some time for the listener loop and flow collection to start
        delay(300)

        notifierSession.notifications.notify("couriers", "the Gauls have crossed the Rhine")

        val notification = withTimeout(2000) {
            notificationDeferred.await()
        }

        assertEquals("couriers", notification.channel)
        assertEquals("the Gauls have crossed the Rhine", notification.payload)

        pollingJob.cancelAndJoin()
        listenerSession.close()
        notifierSession.close()
    }

    @Test
    fun testInterruptibleListener() = runBlocking {
        val listenerSession = openSession()
        val notifierSession = openSession()

        listenerSession.notifications.listen("beacons")

        val listenerJob = launch {
            listenerSession.notifications.startInterruptibleListenerLoop()
        }

        val notificationDeferred = async {
            listenerSession.notifications.messages.first { it.channel == "beacons" }
        }

        // Allow some time for the listener loop and flow collection to start
        delay(300)

        notifierSession.notifications.notify("beacons", "the beacon on the Wall is lit")

        val notification = withTimeout(2000) {
            notificationDeferred.await()
        }

        assertEquals("beacons", notification.channel)
        assertEquals("the beacon on the Wall is lit", notification.payload)

        listenerJob.cancelAndJoin()
        // startInterruptibleListenerLoop closes the socket upon cancellation, so we shouldn't explicitly close it without expecting errors or it's fine.
        // Session aborts on cancel, so listenerSession might be already closed or aborting.
        notifierSession.close()
    }

    @Test
    fun `both listener loops refuse to start inside a transaction and leave the session usable`() = runBlocking {
        val session = openSession()
        session.notifications.listen("excubiae")
        session.autoCommit = false
        session.createNativeQuery("SELECT 1").fetchField<Int>() // begins the transaction on the server

        val polling = assertFailsWith<InvalidOperationException> {
            session.notifications.startPollingListenerLoop(100)
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, polling.reason)
        val interruptible = assertFailsWith<InvalidOperationException> {
            session.notifications.startInterruptibleListenerLoop()
        }
        assertEquals(InvalidOperationExceptionReason.CONNECTION_BUSY, interruptible.reason)

        session.rollback()
        assertEquals(1, session.createNativeQuery("SELECT 1").fetchField<Int>())
        session.close()
    }

    @Test
    fun `a loop on a session whose BEGIN is still waiting receives as it would without one`() = runBlocking {
        val listenerSession = openSession()
        val notifierSession = openSession()
        listenerSession.notifications.listen("tesserae")
        listenerSession.autoCommit = false // sends nothing, so the server stays idle

        val pollingJob = launch {
            listenerSession.notifications.startPollingListenerLoop(100)
        }
        val notificationDeferred = async {
            listenerSession.notifications.messages.first { it.channel == "tesserae" }
        }
        delay(300)

        notifierSession.notifications.notify("tesserae", "the watchword is given")

        val notification = withTimeout(2000) { notificationDeferred.await() }
        assertEquals("the watchword is given", notification.payload)

        pollingJob.cancelAndJoin()
        listenerSession.close()
        notifierSession.close()
    }

    @Test
    fun testPollingListenerThrowsWhenTheServerEndsTheSession() = runBlocking {
        val listenerSession = openSession()
        val adminSession = openSession()

        val pid = listenerSession.createNativeQuery("SELECT pg_backend_pid()").fetchField<Int>()

        val pollingJob = launch {
            // The server says why before it closes, and the loop reports that rather than the close
            val ex = assertFailsWith<SessionTerminatedException> {
                listenerSession.notifications.startPollingListenerLoop(100)
            }
            assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
        }

        delay(300)

        adminSession.createNativeQuery("SELECT pg_terminate_backend($1)").fetchField<Boolean>(pid)

        withTimeout(2000) {
            pollingJob.join()
        }

        adminSession.close()
    }

    @Test
    fun testInterruptibleListenerThrowsWhenTheServerEndsTheSession() = runBlocking {
        val listenerSession = openSession()
        val adminSession = openSession()

        val pid = listenerSession.createNativeQuery("SELECT pg_backend_pid()").fetchField<Int>()

        val listenerJob = launch {
            val ex = assertFailsWith<SessionTerminatedException> {
                listenerSession.notifications.startInterruptibleListenerLoop()
            }
            assertEquals(SessionTerminatedExceptionReason.ADMINISTRATOR_COMMAND, ex.reason)
        }

        delay(300)

        adminSession.createNativeQuery("SELECT pg_terminate_backend($1)").fetchField<Boolean>(pid)

        withTimeout(2000) {
            listenerJob.join()
        }

        adminSession.close()
    }

    @Test
    fun testPollingListenerReportsIdleSessionTimeout() = runBlocking {
        // The timeout runs out on a multiple of the poll, so the FATAL tends to land just as one read has timed
        // out and before the next begins
        assumeUnreadFatalSurvives()
        val listenerSession = openSession()
        listenerSession.notifications.listen("vigiles")
        // A listening session is an idle one as far as the server is concerned
        listenerSession.createNativeQuery("SET idle_session_timeout = '300ms'").execute()

        val ex = withTimeout(5000) {
            assertFailsWith<SessionTerminatedException> {
                listenerSession.notifications.startPollingListenerLoop(100)
            }
        }
        assertEquals(SessionTerminatedExceptionReason.IDLE_SESSION_TIMEOUT, ex.reason)

        listenerSession.close()
    }
}
