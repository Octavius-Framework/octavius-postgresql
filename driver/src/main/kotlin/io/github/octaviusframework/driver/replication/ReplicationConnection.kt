package io.github.octaviusframework.driver.replication

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.StatementException
import io.github.octaviusframework.driver.exception.StatementExceptionReason
import io.github.octaviusframework.driver.identifier.quoteAsPgIdentifier
import io.github.octaviusframework.driver.initialization.OctaviusConnectionFactory
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.backend.CopyBothResponseMessage
import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage
import io.github.octaviusframework.driver.message.backend.ReadyForQueryMessage
import io.github.octaviusframework.driver.message.frontend.SimpleQueryMessage
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Streams the changes of the logical slot [slot] as its output plugin writes them, over a replication connection of
 * its own to [url], with [properties] over what the URL states. The stream starts at [startLsn], or where the slot
 * was last acknowledged where that is later - always, for [PgLsn.INVALID]. [options] go to the plugin as they are.
 * The role needs the `REPLICATION` attribute.
 *
 * @param statusInterval How often the stream tells the server what it has received and what was acknowledged.
 * @throws InvalidOperationException `INVALID_ARGUMENT` if [statusInterval] is not positive.
 * @throws InitializationException if no connection could be opened.
 * @throws StatementException if the server refused the slot, such as one that does not exist.
 */
fun startLogical(
    url: String,
    properties: OctaviusProperties = OctaviusProperties(),
    slot: String,
    options: Map<String, String> = emptyMap(),
    startLsn: PgLsn = PgLsn.INVALID,
    statusInterval: Duration = 10.seconds
): LogicalReplicationStream {
    checkStatusInterval(statusInterval)
    return ReplicationConnection.open(url, properties).streaming { it.start(slot, startLsn, options, statusInterval) }
}

private fun checkStatusInterval(statusInterval: Duration) {
    if (!statusInterval.isPositive()) {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "The status interval must be positive, was $statusInterval."
        )
    }
}

/**
 * A logical replication connection on its way to the one stream it carries, which closes it. PostgreSQL ends a
 * second logical stream on one connection as soon as it starts, since the flags that mark the first one's end are
 * not reset (BUG #18754), so no connection outlives its stream.
 */
internal class ReplicationConnection private constructor(private val stream: PgStream) {

    /**
     * Runs [setup] up to the stream it starts, which takes the connection. Where [setup] fails, closes the connection
     * and throws what it failed with.
     */
    fun <T> streaming(setup: (ReplicationConnection) -> T): T {
        try {
            return setup(this)
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
    }

    /** Starts streaming [slot], which hands the connection to the stream. */
    fun start(slot: String, startLsn: PgLsn, options: Map<String, String>, statusInterval: Duration): LogicalReplicationStream {
        val command = buildString {
            append("START_REPLICATION SLOT ").append(slot.quoteAsPgIdentifier())
            append(" LOGICAL ").append(startLsn)
            if (options.isNotEmpty()) {
                options.entries.joinTo(this, ", ", " (", ")") { (name, value) ->
                    "${name.quoteAsPgIdentifier()} ${value.quoteAsReplicationLiteral()}"
                }
            }
        }
        stream.writeMessage(SimpleQueryMessage(command))
        stream.flush()

        var error: ErrorOrNoticeMessage? = null
        while (true) {
            when (val msg = stream.receiveMessage()) {
                is CopyBothResponseMessage -> {
                    logger.debug { "[PID: ${stream.processId}] Replication started: $command" }
                    return LogicalReplicationStream(stream, statusInterval)
                }
                is ErrorOrNoticeMessage -> error = msg
                is ReadyForQueryMessage -> {
                    if (error != null) throw ExceptionTranslator.translate(error)
                    throw InvalidOperationException(
                        InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                        "START_REPLICATION ended without streaming."
                    )
                }
                else -> { /* Ignore */ }
            }
        }
    }

    companion object {
        /** Opens one to [url], with [properties] over what the URL states. */
        fun open(url: String, properties: OctaviusProperties): ReplicationConnection {
            val merged = OctaviusProperties.parse(url)
            merged.merge(properties)
            return ReplicationConnection(OctaviusConnectionFactory.createReplicationStream(merged))
        }
    }
}

/**
 * This string as a literal of the replication command language, which has no escapes but a doubled quote.
 *
 * @throws StatementException if it holds a NUL character.
 */
private fun String.quoteAsReplicationLiteral(): String {
    if ('\u0000' in this) {
        throw StatementException(StatementExceptionReason.SYNTAX_ERROR, details = "A replication option cannot contain the NUL (\\0) character.")
    }
    return "'" + replace("'", "''") + "'"
}
