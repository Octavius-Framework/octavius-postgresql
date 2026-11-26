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
import io.github.octaviusframework.driver.message.backend.DataRowMessage
import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage
import io.github.octaviusframework.driver.message.backend.ReadyForQueryMessage
import io.github.octaviusframework.driver.message.backend.RowDescriptionMessage
import io.github.octaviusframework.driver.message.frontend.SimpleQueryMessage
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.session.SearchPath
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Streams the committed transactions of the logical slot [slot], made with `pgoutput`, that changed the tables of
 * [publications], over a replication connection of its own to [url], with [properties] over what the URL states.
 * Their rows are read as the rows of a query are. The role needs the `REPLICATION` attribute.
 *
 * With [newSlot] the slot is created first, and its copy runs before the stream starts; a persistent slot whose copy
 * or start fails is dropped again. Otherwise the stream starts at [startLsn], or where the slot was last acknowledged
 * where that is later - always, for [PgLsn.INVALID].
 *
 * The rows, and the copy, are read through [typeSource]'s sessions and its type catalog, or without one, through
 * sessions opened on [url]. A type the catalog does not describe, such as one created after it was read, ends the
 * stream with `TypeException(TYPE_NOT_FOUND)`, as it fails a query, until `reloadTypes()` reads it.
 *
 * @param statusInterval How often the stream tells the server what it has received and what was acknowledged.
 * @throws InvalidOperationException `INVALID_ARGUMENT` if [publications] is empty, [statusInterval] is not positive,
 *   [newSlot] comes with a [startLsn], or [typeSource] reaches another database than [url].
 * @throws InitializationException if no connection could be opened.
 * @throws StatementException if the server refused the slot: a new one that exists, or one that does not.
 * @throws Throwable what [NewSlot.copy] threw, once the slot it was copying for is gone.
 */
fun startPgOutput(
    url: String,
    properties: OctaviusProperties = OctaviusProperties(),
    slot: String,
    publications: List<String>,
    newSlot: NewSlot? = null,
    startLsn: PgLsn = PgLsn.INVALID,
    statusInterval: Duration = 10.seconds,
    typeSource: DataSource? = null
): ChangeStream {
    checkStatusInterval(statusInterval)
    if (publications.isEmpty()) {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "A pgoutput stream needs at least one publication."
        )
    }
    if (newSlot != null && startLsn != PgLsn.INVALID) {
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "A new slot streams from where it was made, so it takes no start position; $startLsn was given."
        )
    }
    val options = buildMap {
        put("proto_version", "1")
        put("publication_names", publications.joinToString(",") { it.quoteAsPgIdentifier() })
        put("binary", "true")
    }
    return ReplicationConnection.open(url, properties).streaming { connection ->
        // Before the slot: any command after it ends the snapshot it exports.
        val types = connection.streamTypes(typeSource)
        if (newSlot != null) {
            val snapshot = connection.createSlot(slot, "pgoutput", newSlot.temporary, exportSnapshot = newSlot.copy != null)
            newSlot.copy?.let { copy ->
                types.session().use { session ->
                    session.transaction.required(
                        isolation = TransactionIsolationLevel.REPEATABLE_READ,
                        readOnly = true,
                        snapshot = snapshot
                    ) { copy() }
                }
            }
        }
        ChangeStream(connection.start(slot, startLsn, options, statusInterval), PgOutputDecoder(types), statusInterval)
    }
}

/**
 * Streams the changes of the logical slot [slot] as its output plugin writes them, over a replication connection of
 * its own to [url], with [properties] over what the URL states. [startLsn] is as for [startPgOutput], and [options]
 * go to the plugin as they are.
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
internal class ReplicationConnection private constructor(
    private val stream: PgStream,
    private val properties: OctaviusProperties
) {
    /** The persistent slot [createSlot] made, which a failure before the stream starts drops again. */
    private var made: String? = null

    /**
     * Runs [setup] up to the stream it starts, which takes the connection. Where [setup] fails, drops the slot it
     * made, closes the connection and throws what it failed with.
     */
    fun <T> streaming(setup: (ReplicationConnection) -> T): T {
        try {
            return setup(this)
        } catch (e: Throwable) {
            made?.takeIf { !stream.isBroken }?.let { slot ->
                try {
                    command("DROP_REPLICATION_SLOT ${slot.quoteAsPgIdentifier()}", expectsRow = false)
                    logger.debug { "[PID: ${stream.processId}] Replication slot dropped after a failed start: $slot" }
                } catch (dropFailure: Exception) {
                    e.addSuppressed(dropFailure)
                }
            }
            stream.close()
            throw e
        }
    }

    /**
     * The type catalog a pgoutput stream reads with: [typeSource]'s, or the one of this connection's URL.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [typeSource] reaches another database.
     */
    fun streamTypes(typeSource: DataSource?): StreamTypes {
        val lookupOrder = SearchPath(
            stream.parameters.getValue("search_path"),
            stream.parameters["session_authorization"]
        ).typeLookupOrder
        if (typeSource == null) return StreamTypes.direct(properties, lookupOrder)
        val (pooled, identity) = StreamTypes.pooled(typeSource, lookupOrder)
        checkSameDatabase(identity)
        return pooled
    }

    /**
     * Creates the logical slot [slot], decoded by [plugin], and returns the name of the snapshot it exports where
     * [exportSnapshot] asks for one. A persistent slot is dropped again if the stream does not start.
     */
    fun createSlot(slot: String, plugin: String, temporary: Boolean, exportSnapshot: Boolean): String? {
        val command = buildString {
            append("CREATE_REPLICATION_SLOT ").append(slot.quoteAsPgIdentifier())
            if (temporary) append(" TEMPORARY")
            append(" LOGICAL ").append(plugin.quoteAsPgIdentifier())
            append(if (exportSnapshot) " (SNAPSHOT 'export')" else " (SNAPSHOT 'nothing')")
        }
        val row = command(command, expectsRow = true)
        if (!temporary) made = slot
        logger.debug { "[PID: ${stream.processId}] Replication slot created: $command" }
        return row["snapshot_name"]
    }

    /** Refuses a type source whose sessions reach another database than this connection, by its OIDs. */
    private fun checkSameDatabase(identity: DatabaseIdentity) {
        val row = command("IDENTIFY_SYSTEM", expectsRow = true)
        val systemIdentifier = row["systemid"]?.toULongOrNull()?.toLong() ?: unexpected("IDENTIFY_SYSTEM")
        val database = row["dbname"]
        if (systemIdentifier == identity.systemIdentifier && database == identity.database) return
        throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            "The type source reaches the database '${identity.database}' of the cluster " +
                "${java.lang.Long.toUnsignedString(identity.systemIdentifier)}, and this connection '$database' of " +
                "${java.lang.Long.toUnsignedString(systemIdentifier)}. Its catalog maps types by OID, which only " +
                "the same database, or a physical standby of it, shares."
        )
    }

    /**
     * Runs the replication command [command] and returns its one row as text by column name, or an empty map where
     * it returns none and [expectsRow] is false.
     */
    private fun command(command: String, expectsRow: Boolean): Map<String, String?> {
        stream.writeMessage(SimpleQueryMessage(command))
        stream.flush()

        var columns = emptyList<String>()
        var row: Map<String, String?>? = null
        var error: ErrorOrNoticeMessage? = null
        while (true) {
            when (val msg = stream.receiveMessage()) {
                is RowDescriptionMessage -> columns = msg.fields.map { it.name }
                is DataRowMessage -> row = columns.withIndex().associate { (i, name) ->
                    val length = msg.columnLengths[i]
                    name to if (length < 0) null else String(msg.rawData, msg.columnOffsets[i], length, Charsets.UTF_8)
                }
                is ErrorOrNoticeMessage -> error = msg
                is ReadyForQueryMessage -> break
                else -> { /* Ignore */ }
            }
        }
        if (error != null) throw ExceptionTranslator.translate(error)
        return row ?: if (expectsRow) unexpected(command) else emptyMap()
    }

    private fun unexpected(command: String): Nothing = throw InvalidOperationException(
        InvalidOperationExceptionReason.UNEXPECTED_RESULT,
        "${command.substringBefore(' ')} did not return the row it describes."
    )

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
            return ReplicationConnection(OctaviusConnectionFactory.createReplicationStream(merged), merged)
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
