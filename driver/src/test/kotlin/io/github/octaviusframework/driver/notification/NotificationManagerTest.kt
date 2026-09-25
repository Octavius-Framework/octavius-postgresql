package io.github.octaviusframework.driver.notification

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.github.octaviusframework.driver.exception.NetworkException

class NotificationManagerTest : AbstractIntegrationTest() {

    @Test
    fun testPollingListener() = runBlocking {
        val listenerSession = openSession()
        val notifierSession = openSession()

        listenerSession.notifications.listen("test_channel")

        val pollingJob = launch {
            listenerSession.notifications.startPollingListenerLoop(100)
        }

        val notificationDeferred = async {
            listenerSession.notifications.messages.first { it.channel == "test_channel" }
        }

        // Allow some time for the listener loop and flow collection to start
        delay(300)

        notifierSession.notifications.notify("test_channel", "hello_polling")

        val notification = withTimeout(2000) {
            notificationDeferred.await()
        }

        assertEquals("test_channel", notification.channel)
        assertEquals("hello_polling", notification.payload)

        pollingJob.cancelAndJoin()
        listenerSession.close()
        notifierSession.close()
    }

    @Test
    fun testInterruptibleListener() = runBlocking {
        val listenerSession = openSession()
        val notifierSession = openSession()

        listenerSession.notifications.listen("test_channel_int")

        val listenerJob = launch {
            listenerSession.notifications.startInterruptibleListenerLoop()
        }

        val notificationDeferred = async {
            listenerSession.notifications.messages.first { it.channel == "test_channel_int" }
        }

        // Allow some time for the listener loop and flow collection to start
        delay(300)

        notifierSession.notifications.notify("test_channel_int", "hello_interruptible")

        val notification = withTimeout(2000) {
            notificationDeferred.await()
        }

        assertEquals("test_channel_int", notification.channel)
        assertEquals("hello_interruptible", notification.payload)

        listenerJob.cancelAndJoin()
        // startInterruptibleListenerLoop closes the socket upon cancellation, so we shouldn't explicitly close it without expecting errors or it's fine.
        // Session aborts on cancel, so listenerSession might be already closed or aborting.
        notifierSession.close()
    }

    @Test
    fun testPollingListenerThrowsOnNetworkError() = runBlocking {
        val listenerSession = openSession()
        val adminSession = openSession()

        val pid = listenerSession.createNativeQuery("SELECT pg_backend_pid()").fetchField<Int>()

        val pollingJob = launch {
            assertFailsWith<NetworkException> {
                listenerSession.notifications.startPollingListenerLoop(100)
            }
        }

        delay(300)

        // Kill the backend to simulate network error/dropped connection
        adminSession.createNativeQuery("SELECT pg_terminate_backend($1)").fetchField<Boolean>(pid)

        withTimeout(2000) {
            pollingJob.join()
        }

        adminSession.close()
    }

    @Test
    fun testInterruptibleListenerThrowsOnNetworkError() = runBlocking {
        val listenerSession = openSession()
        val adminSession = openSession()

        val pid = listenerSession.createNativeQuery("SELECT pg_backend_pid()").fetchField<Int>()

        val listenerJob = launch {
            assertFailsWith<NetworkException> {
                listenerSession.notifications.startInterruptibleListenerLoop()
            }
        }

        delay(300)

        // Kill the backend to simulate network error/dropped connection
        adminSession.createNativeQuery("SELECT pg_terminate_backend($1)").fetchField<Boolean>(pid)

        withTimeout(2000) {
            listenerJob.join()
        }

        adminSession.close()
    }
}
