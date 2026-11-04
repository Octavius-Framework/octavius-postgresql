package io.github.octaviusframework.driver.notification

import io.github.octaviusframework.driver.concurrent.OctaviusDispatchers
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import io.github.octaviusframework.driver.session.TransactionState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * `LISTEN` and `NOTIFY` on this session. Notifications arrive on [messages] while the connection is read: during
 * any query, or in a listener loop.
 */
class NotificationManager internal constructor(private val session: OctaviusSessionImpl) {

    private val connection get() = session.octaviusConnection

    /** Ties a log line to the backend this manager listens on. */
    private val pid: String get() = "[PID: ${connection.stream.processId}]"

    /** The notifications received on this connection. */
    val messages: SharedFlow<PgNotification>
        get() = connection.stream.notifications

    /**
     * Reads the connection until the coroutine is cancelled, waking every [pollTimeoutMs] to check. Cancelling
     * leaves the connection usable.
     *
     * @param dispatcher `null` for virtual threads.
     * @throws InvalidOperationException `CONNECTION_BUSY` where a transaction is open, which would hold back every
     * notification.
     */
    suspend fun startPollingListenerLoop(pollTimeoutMs: Int = 500, dispatcher: CoroutineDispatcher? = null) {
        if (connection.isClosedFlag) return
        refuseOpenTransaction()

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
     * Reads the connection without waking until the coroutine is cancelled, which aborts the connection.
     *
     * @param dispatcher `null` for virtual threads.
     * @throws InvalidOperationException `CONNECTION_BUSY` where a transaction is open; the connection is left as
     * it was.
     */
    suspend fun startInterruptibleListenerLoop(dispatcher: CoroutineDispatcher? = null) {
        if (connection.isClosedFlag) return
        // Before anything that could abort the connection on the way out: a refusal leaves it as it was.
        refuseOpenTransaction()

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
     * Refuses a loop that would wait for nothing: the server holds notifications back until a transaction ends.
     * Reads the server's state, so a `BEGIN` still waiting does not count.
     */
    private fun refuseOpenTransaction() {
        if (connection.serverTransactionState == TransactionState.IDLE) return
        throw InvalidOperationException(
            InvalidOperationExceptionReason.CONNECTION_BUSY,
            details = "A transaction is open on this connection, and PostgreSQL delivers no notification to it until " +
                "the transaction ends - which nothing could do while the loop holds the connection. Commit or roll " +
                "back before starting the loop."
        )
    }

    /** Whether this session has subscribed to anything, which [releaseSubscriptions] undoes with `UNLISTEN *`. */
    @Volatile
    private var hasSubscribed: Boolean = false

    /** Listens on [channels]. */
    fun listen(vararg channels: String) {
        if (channels.isEmpty()) return
        val sql = channels.joinToString("; ") { "LISTEN ${it.quoteAsPgIdentifier()}" }
        session.createNativeQuery(sql).execute()
        hasSubscribed = true
        logger.debug { "$pid Listening on ${channels.joinToString(", ")}" }
    }

    /** Stops listening on [channels]. */
    fun unlisten(vararg channels: String) {
        if (channels.isEmpty()) return
        val sql = channels.joinToString("; ") { "UNLISTEN ${it.quoteAsPgIdentifier()}" }
        session.createNativeQuery(sql).execute()
        logger.debug { "$pid Stopped listening on ${channels.joinToString(", ")}" }
    }

    /** Stops listening on every channel. */
    fun unlistenAll() {
        session.createNativeQuery("UNLISTEN *").execute()
        hasSubscribed = false
        logger.debug { "$pid Stopped listening on all channels" }
    }

    /** Sends a notification on [channel]. */
    fun notify(channel: String, payload: String? = null) {
        session.createNativeQuery("SELECT pg_notify($1, $2)").fetchField<Unit>(channel, payload)
    }

    /**
     * Drops this session's subscriptions before its connection goes to the next borrower. Never opens a waiting
     * transaction, since inside one `UNLISTEN` takes effect only at commit.
     */
    internal fun releaseSubscriptions() {
        if (!hasSubscribed) return
        connection.queryExecutor.control("UNLISTEN *")
        hasSubscribed = false
        logger.debug { "$pid Stopped listening on all channels" }
    }
}