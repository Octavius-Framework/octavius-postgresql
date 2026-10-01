package io.github.octaviusframework.driver.notification

import io.github.octaviusframework.driver.concurrent.OctaviusDispatchers
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Manages asynchronous notifications (`LISTEN` / `NOTIFY`) for a specific database session.
 *
 * This manager provides methods to subscribe to and unsubscribe from PostgreSQL notification channels,
 * emit new notifications, and start listener loops to receive incoming messages via a Flow.
 */
class NotificationManager internal constructor(private val session: OctaviusSessionImpl) {

    private val connection get() = session.octaviusConnection

    /** Ties a log line to the backend this manager listens on. */
    private val pid: String get() = "[PID: ${connection.stream.processId}]"

    /**
     * A [SharedFlow] of asynchronous notifications (LISTEN/NOTIFY) received from the database.
     */
    val messages: SharedFlow<PgNotification>
        get() = connection.stream.notifications

    /**
     * Starts a listener loop using active polling with a socket timeout.
     * When the coroutine is cancelled, the loop exits gracefully without closing
     * the underlying database connection, allowing it to be reused.
     *
     * @param pollTimeoutMs The socket timeout used for polling in milliseconds.
     * @param dispatcher The coroutine dispatcher to run the loop on. Defaults to a virtual thread dispatcher if null.
     */
    suspend fun startPollingListenerLoop(pollTimeoutMs: Int = 500, dispatcher: CoroutineDispatcher? = null) {
        if (connection.isClosedFlag) return

        withContext(dispatcher ?: OctaviusDispatchers.Virtual) {
            val context = currentCoroutineContext()
            connection.stream.lock.withLock {
                connection.stream.checkAvailable()
                val originalTimeout = connection.stream.networkTimeout
                try {
                    connection.stream.networkTimeout = pollTimeoutMs
                    logger.debug { "$pid Polling listener loop started (timeout ${pollTimeoutMs}ms)" }

                    while (context.isActive && !connection.isClosedFlag) {
                        try {
                            connection.stream.pollMessage()
                        } catch (e: NetworkException) {
                            if (connection.isClosedFlag || !context.isActive) {
                                // Connection closed explicitly or coroutine cancelled
                                break
                            } else {
                                // Connection dropped by network or server
                                throw e
                            }
                        }
                    }
                } finally {
                    try {
                        if (!connection.isClosedFlag) connection.stream.networkTimeout = originalTimeout
                    } catch (e: Exception) {
                        logger.trace(e) { "$pid Could not restore the network timeout; the connection is already gone" }
                    }
                    logger.debug { "$pid Polling listener loop stopped" }
                }
            }
        }
    }

    /**
     * Starts a listener loop that blocks indefinitely.
     * When the coroutine is cancelled, it simply closes the socket.
     *
     * @param dispatcher The coroutine dispatcher to run the loop on. Defaults to a virtual thread dispatcher if null.
     */
    suspend fun startInterruptibleListenerLoop(dispatcher: CoroutineDispatcher? = null) {
        if (connection.isClosedFlag) return

        withContext(dispatcher ?: OctaviusDispatchers.Virtual) {
            val cancelJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    session.abort()
                }
            }

            val context = currentCoroutineContext()
            connection.stream.lock.withLock {
                connection.stream.checkAvailable()
                try {
                    connection.stream.networkTimeout = 0
                    logger.debug { "$pid Interruptible listener loop started" }

                    while (context.isActive && !connection.isClosedFlag) {
                        try {
                            connection.stream.receiveMessage()
                        } catch (e: NetworkException) {
                            if (connection.isClosedFlag || !context.isActive) {
                                break
                            } else {
                                throw e
                            }
                        }
                    }
                } finally {
                    logger.debug { "$pid Interruptible listener loop stopped; aborting the connection" }
                    cancelJob.cancel()
                    session.abort()
                }
            }
        }
    }

    /**
     * Whether this session has ever subscribed to anything.
     *
     * Subscriptions live on the physical connection, which outlives the session when it came
     * from a pool, so the session has to know whether it left any behind. Tracked as a plain
     * "ever subscribed" flag rather than a set of channels: `UNLISTEN *` is idempotent, and
     * one redundant round trip on close is cheaper than keeping an accurate tally.
     */
    @Volatile
    private var hasSubscribed: Boolean = false

    /**
     * Registers this connection to listen for notifications on the specified channel(s).
     */
    fun listen(vararg channels: String) {
        if (channels.isEmpty()) return
        val sql = channels.joinToString("; ") { "LISTEN ${it.quoteAsPgIdentifier()}" }
        session.createNativeQuery(sql).execute()
        hasSubscribed = true
        logger.debug { "$pid Listening on ${channels.joinToString(", ")}" }
    }

    /**
     * Stops listening for notifications on the specified channel(s).
     */
    fun unlisten(vararg channels: String) {
        if (channels.isEmpty()) return
        val sql = channels.joinToString("; ") { "UNLISTEN ${it.quoteAsPgIdentifier()}" }
        session.createNativeQuery(sql).execute()
        logger.debug { "$pid Stopped listening on ${channels.joinToString(", ")}" }
    }

    /**
     * Stops listening for all notifications on this connection.
     */
    fun unlistenAll() {
        session.createNativeQuery("UNLISTEN *").execute()
        hasSubscribed = false
        logger.debug { "$pid Stopped listening on all channels" }
    }

    /**
     * Sends a notification to the specified channel, optionally with a payload string.
     */
    fun notify(channel: String, payload: String? = null) {
        session.createNativeQuery("SELECT pg_notify($1, $2)").fetchField<Unit>(channel, payload)
    }

    /**
     * Drops whatever this session subscribed to, if anything.
     *
     * Called when a session closes over a connection that will outlive it: leaving the
     * registrations in place would hand the next borrower of a pooled connection someone
     * else's subscriptions, and let them pile up over the connection's lifetime. Sessions
     * that never called [listen] pay nothing.
     *
     * Sent as a statement about the connection rather than as the session's own work, so that it never
     * opens the transaction a `BEGIN` may be waiting for. Inside one, `UNLISTEN` would take effect only at a
     * commit - and a session being closed has no commit coming, only the rollback of what it left open.
     */
    internal fun releaseSubscriptions() {
        if (!hasSubscribed) return
        connection.queryExecutor.control("UNLISTEN *")
        hasSubscribed = false
        logger.debug { "$pid Stopped listening on all channels" }
    }
}