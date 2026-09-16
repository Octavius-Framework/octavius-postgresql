package io.github.octaviusframework.driver.execution

import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.*
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.*
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.row.RowMetadata
import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.util.formatDiagnosticValue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Handles the low-level execution of PostgreSQL queries over the wire protocol.
 *
 * This executor manages communication with the database using both the Simple Query Protocol
 * (for utility statements) and the Extended Query Protocol (for parameterized DML and DQL).
 * It is responsible for parsing responses, managing the transaction state flag, and mapping
 * results back into usable domain objects.
 */
internal class QueryExecutor(
    private val stream: PgStream,
    maxParameterWriterCapacity: Int?,
    initialParameterWriterCapacity: Int?,
    private val logParameterValues: Boolean
) {
    private val parameterWriter = PgByteWriter(
        initialCapacity = initialParameterWriterCapacity ?: 1024,
        maxCapacity = maxParameterWriterCapacity ?: 65536
    )

    private companion object {
        /**
         * The name every statement and every portal here goes out under.
         *
         * Nothing is prepared server-side and nothing outlives its exchange, so there is only ever
         * one of each in flight on a connection and it needs no name to be told apart.
         */
        const val UNNAMED = ""

        /**
         * `[1]` - binary, for every parameter and every result column alike.
         *
         * A one-element list is how the protocol says "this format, for all of them", and it is
         * held rather than built because `listOf(1)` at the call site is two allocations on every
         * statement the driver executes.
         */
        val BINARY_FORMAT = listOf(1)
    }

    /** Ties a log line to the backend this executor talks to. */
    private val pid: String get() = "[PID: ${stream.processId}]"

    /**
     * Notes a statement on its way out and returns the clock reading [traceDone] measures against.
     *
     * The clock is only read once the level is known to be on, so a statement running with tracing
     * off pays a single flag check and nothing more - which matters here and nowhere else in the
     * driver, this being the one method every query goes through.
     */
    private fun traceStart(sql: String, params: Array<out Any?>): Long {
        if (!logger.isTraceEnabled()) return 0L
        logger.trace {
            if (sql.isEmpty()) "$pid > (empty query)"
            else "$pid >" + describeParameters(params) + "\n$sql"
        }
        return System.nanoTime()
    }

    /**
     * The parameter part of a traced statement's header line.
     *
     * Without [logParameterValues] this is a count and nothing more, because the values are the
     * contents of your tables and a log file is the wrong place for them by default. With it, they
     * are numbered to match the `$n` placeholders in the statement printed underneath, and each is
     * truncated exactly as it would be inside an exception - the same renderer does both.
     */
    private fun describeParameters(params: Array<out Any?>): String {
        if (params.isEmpty()) return ""
        if (!logParameterValues) return " (${params.size} params)"
        return params.mapIndexed { index, value -> "\$${index + 1}=${formatDiagnosticValue(value)}" }
            .joinToString(", ", prefix = " (", postfix = ")")
    }

    /**
     * Closes the pair opened by [traceStart], reporting what the statement produced.
     *
     * Only reached when the statement succeeded: a failure carries its own SQL and parameters in
     * the exception, so logging it here would print the same diagnostic twice.
     *
     * [outcome] is a lambda and this function is inline for one reason: passing the description as
     * a plain `String` builds it at every call site whether anything will read it.
     */
    private inline fun traceDone(startedAt: Long, crossinline outcome: () -> String) {
        if (startedAt == 0L || !logger.isTraceEnabled()) return
        val elapsedNanos = System.nanoTime() - startedAt
        logger.trace { "$pid < ${outcome()} in ${formatMillis(elapsedNanos)}" }
    }

    /**
     * Renders a duration as milliseconds with three decimals, always.
     *
     * A single unit across every statement is what makes the column sortable and two lines
     * comparable at a glance; switching to seconds once a query gets slow would mean reading the
     * suffix before you can tell which of two numbers is bigger. Three decimals keep the sub-
     * millisecond statements - most of them, on a local server - from all collapsing onto `0`.
     *
     * [Locale.ROOT] is not optional: the default locale decides the decimal separator.
     */
    private fun formatMillis(elapsedNanos: Long): String =
        String.format(Locale.ROOT, "%.3fms", elapsedNanos / 1_000_000.0)

    /**
     * Runs one exchange with the server: takes the connection, marks it busy, and releases it
     * whatever happens.
     *
     * The busy mark is what makes a reentrant call fail cleanly instead of interleaving its
     * messages into an exchange already in flight - the lock alone cannot, being reentrant.
     */
    private inline fun <T> exchange(block: () -> T): T = stream.lock.withLock {
        stream.beginExchange()
        try {
            block()
        } finally {
            stream.endExchange()
        }
    }

    /**
     * Sends the opening of an Extended Query exchange: `Parse`, `Bind` and a `Describe` of the portal.
     *
     * Stops there because what follows is the one thing the three callers disagree on - a single
     * `Execute` and a `Sync` for a result read whole, an `Execute` per batch for one streamed.
     * Serializing the parameters is part of this: it fills [parameterWriter], which the `Bind` built
     * here is the only reader of.
     */
    private fun sendParseBindDescribe(
        sql: String,
        params: Array<out Any?>,
        parameterSerializer: ParameterSerializer?
    ) {
        val paramTypes = parameterSerializer?.serializeAll(params, parameterWriter) ?: IntArray(0)
        val paramValues = if (parameterSerializer != null) parameterWriter.data else ByteArray(0)
        val paramValuesLength = if (parameterSerializer != null) parameterWriter.position else 0

        stream.sendMessage(ParseMessage(UNNAMED, sql, paramTypes))
        stream.sendMessage(
            BindMessage(
                UNNAMED, UNNAMED, params.size, paramValues, BINARY_FORMAT, BINARY_FORMAT, paramValuesLength
            )
        )
        stream.sendMessage(DescribeMessage('P', UNNAMED))
    }

    /**
     * Uses Simple Query Protocol (Q).
     * Intended for calls that do not return results or where results are ignored (e.g., SET TIME ZONE, BEGIN).
     *
     * Rows are read and dropped whatever [ignoreRows] says - the exchange has to reach
     * [ReadyForQueryMessage] before this connection is usable again, and that means draining whatever the
     * server sent. What [ignoreRows] governs is only whether their arrival is reported as a mistake.
     */
    fun execute(sql: String, ignoreRows: Boolean = false) = exchange {
        val startedAt = traceStart(sql, emptyArray())

        stream.sendMessage(SimpleQueryMessage(sql))
        stream.flush()

        var errorResponse: ErrorOrNoticeMessage? = null
        var executionError: OctaviusException? = null
        while (true) {
            val msg = stream.receiveMessage()
            when (msg) {
                is ErrorOrNoticeMessage -> errorResponse = msg
                is ReadyForQueryMessage -> break
                is RowDescriptionMessage, is DataRowMessage -> {
                    if (!ignoreRows && errorResponse == null && executionError == null) {
                        executionError = InvalidOperationException(
                            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                            "Method execute() received result rows. Use query() for DQL queries, " +
                                "or execute(ignoreRows = true) for a script whose rows are not wanted."
                        )
                    }
                }
                is CommandCompleteMessage, is EmptyQueryResponseMessage -> { /* Ignore - expected */ }
                else -> { /* Ignore */ }
            }
        }

        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        traceDone(startedAt) { "done" }
    }

    /**
     * Uses Extended Query Protocol (Parse, Bind, Execute, Sync).
     * Intended for DML (INSERT, UPDATE, DELETE). Expects no rows returned.
     * Returns the number of updated rows.
     */
    fun update(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer? = null
    ): Long = exchange {
        val startedAt = traceStart(sql, params)

        sendParseBindDescribe(sql, params, parameterSerializer)
        stream.sendMessage(ExecuteMessage(UNNAMED, 0))
        stream.sendMessage(SyncMessage())

        stream.flush()

        var rowsAffected = 0L
        var errorResponse: ErrorOrNoticeMessage? = null
        var executionError: OctaviusException? = null
        
        while (true) {
            val msg = stream.receiveMessage()
            when (msg) {
                is ParseCompleteMessage, is BindCompleteMessage, is NoDataMessage -> { /* Expected */ }
                is CommandCompleteMessage -> {
                    // tag format is e.g., "INSERT 0 1", "UPDATE 5", "DELETE 2"
                    val tag = msg.tag
                    val lastSpace = tag.lastIndexOf(' ')
                    if (lastSpace != -1) {
                        var parsed = 0L
                        for (i in lastSpace + 1 until tag.length) {
                            val c = tag[i]
                            if (c in '0'..'9') {
                                parsed = parsed * 10 + (c - '0')
                            } else {
                                parsed = 0L
                                break
                            }
                        }
                        rowsAffected = parsed
                    }
                }
                is DataRowMessage, is RowDescriptionMessage -> {
                    if (errorResponse == null && executionError == null) {
                        executionError = InvalidOperationException(
                            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                            "Method update() received result rows. Use query() for DQL queries."
                        )
                    }
                }
                // Not guarded against a second error, because there cannot be a second one: an error
                // anywhere in an extended-query exchange makes the server discard every message that
                // follows it up to the Sync sent above, and answer that with ReadyForQuery.
                is ErrorOrNoticeMessage -> errorResponse = msg
                is ReadyForQueryMessage -> break
                else -> { /* Ignore */ }
            }
        }

        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        // Copied to a val first, and the copy is the whole point. `logger.trace {}` is an ordinary
        // interface method taking a Function0, so [traceDone]'s message really is an object however
        // inline the rest of it is - and a lambda capturing a mutable var cannot copy its value,
        // since the var keeps moving. Kotlin's answer is to relocate the var into a Ref.LongRef on
        // the heap, allocated *where the var is declared* - which is above the level check, not
        // below it. Guarding the lambda therefore guards nothing: 24 bytes per statement had
        // already been spent by the time anything asked whether tracing was on. A val is captured
        // by value instead, so the only allocation left is the lambda, which the guard does cover.
        val affected = rowsAffected
        traceDone(startedAt) { "$affected rows affected" }
        return rowsAffected
    }

    /**
     * Uses Extended Query Protocol.
     * Intended for DQL (SELECT).
     * Returns a parsed list of rows (Row) immediately.
     */
    fun query(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer? = null,
        mapper: ResultMapper,
        maxRows: Int = 0
    ): List<Row> = query(sql, params, parameterSerializer, mapper, maxRows) { it }

    /**
     * Uses Extended Query Protocol.
     * Intended for DQL (SELECT).
     * Returns a parsed list of elements using the provided transform function immediately.
     */
    fun <R> query(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer?,
        mapper: ResultMapper,
        maxRows: Int = 0,
        transform: (Row) -> R
    ): List<R> = exchange {
        val startedAt = traceStart(sql, params)

        sendParseBindDescribe(sql, params, parameterSerializer)
        stream.sendMessage(ExecuteMessage(UNNAMED, maxRows))
        stream.sendMessage(SyncMessage())

        stream.flush()

        val rows = mutableListOf<R>()
        var rowMetadata: RowMetadata? = null
        var errorResponse: ErrorOrNoticeMessage? = null
        var executionError: OctaviusException? = null
        
        while (true) {
            val msg = stream.receiveMessage()
            when (msg) {
                is ParseCompleteMessage, is BindCompleteMessage, is PortalSuspendedMessage -> { /* Expected */ }
                is RowDescriptionMessage -> {
                    try {
                        rowMetadata = RowMetadata(msg.fields, mapper.catalog.dictionary)
                    } catch (e: OctaviusException) {
                        if (executionError == null) executionError = e
                    }
                }
                is NoDataMessage -> { /* Expected if query returns no rows */ }
                is DataRowMessage -> {
                    if (rowMetadata == null) {
                        if (executionError == null) {
                            executionError = InvalidOperationException(
                                InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                                "Received DataRow before RowDescription"
                            )
                        }
                    } else if (executionError == null && errorResponse == null) {
                        try {
                            rows.add(transform(
                                Row(
                                    msg.rawData,
                                    msg.columnOffsets,
                                    msg.columnLengths,
                                    rowMetadata,
                                    mapper.catalog,
                                    mapper
                                )
                            ))
                        } catch (e: OctaviusException) {
                            executionError = e
                        } catch (e: Exception) {
                            executionError = MappingException(
                                MappingExceptionReason.CONVERSION_ERROR,
                                "Exception in row mapping: ${e.message}",
                                e
                            )
                        }
                    }
                }
                is CommandCompleteMessage -> { /* Ignored in DQL queries */ }
                // Unguarded for the same reason as in update(): one Sync, so at most one error.
                is ErrorOrNoticeMessage -> errorResponse = msg
                is ReadyForQueryMessage -> break
                else -> { /* Ignore */ }
            }
        }
        
        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        traceDone(startedAt) { "${rows.size} rows" }
        return rows
    }

    /**
     * Uses Extended Query Protocol.
     * Intended for DQL (SELECT).
     * Iterates over rows in batches of fetchSize and applies the given transform and block.
     * A [fetchSize] of `0` is `Execute`'s own "no limit": one batch carrying the whole result.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [fetchSize] is negative.
     */
    fun <R> queryForEach(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer,
        mapper: ResultMapper,
        fetchSize: Int,
        transform: (Row) -> R,
        block: (R) -> Unit
    ) {
        // Checked before the lock rather than inside the exchange: taking it means waiting for
        // whatever this connection is already running, and an argument that was never going to be
        // accepted should not spend a long query finding that out.
        if (fetchSize < 0) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "fetchSize cannot be negative, was $fetchSize. State how many rows a batch should pull, " +
                "or 0 for the whole result in a single Execute."
        )

        exchange {
            val startedAt = traceStart(sql, params)

            sendParseBindDescribe(sql, params, parameterSerializer)

            var rowMetadata: RowMetadata? = null
            var errorResponse: ErrorOrNoticeMessage? = null
            var executionError: OctaviusException? = null
            // Counted unconditionally rather than behind the trace flag: an increment costs nothing
            // next to parsing the row it counts, and a branch per row would cost more.
            var rowCount = 0L

            fetchLoop@ while (true) {
                stream.sendMessage(ExecuteMessage(UNNAMED, fetchSize))
                stream.sendMessage(FlushMessage())
                stream.flush()

                msgLoop@ while (true) {
                    when (val msg = stream.receiveMessage()) {
                        is ParseCompleteMessage, is BindCompleteMessage -> { /* Expected */ }
                        is RowDescriptionMessage -> {
                            try {
                                rowMetadata = RowMetadata(msg.fields, mapper.catalog.dictionary)
                            } catch (e: OctaviusException) {
                                executionError = e
                                break@fetchLoop
                            }
                        }
                        is DataRowMessage -> {
                            if (rowMetadata == null) {
                                executionError = InvalidOperationException(
                                    InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                                    "Received DataRow before RowDescription"
                                )
                                break@fetchLoop
                            } else {
                                try {
                                    block(transform(Row(msg.rawData, msg.columnOffsets, msg.columnLengths, rowMetadata, mapper.catalog, mapper)))
                                    rowCount++
                                } catch (e: OctaviusException) {
                                    executionError = e
                                    break@fetchLoop
                                } catch (e: Exception) {
                                    executionError = MappingException(
                                        MappingExceptionReason.BLOCK_FAILED,
                                        "Exception in block: ${e.message}",
                                        e
                                    )
                                    break@fetchLoop
                                }
                            }
                        }
                        is PortalSuspendedMessage -> {
                            break@msgLoop
                        }
                        is NoDataMessage, is CommandCompleteMessage -> {
                            break@fetchLoop
                        }
                        is ErrorOrNoticeMessage -> {
                            errorResponse = msg
                            break@fetchLoop
                        }
                        else -> { /* Ignore */ }
                    }
                }
            }

            stream.sendMessage(SyncMessage())
            stream.flush()
        
            while (true) {
                val msg = stream.receiveMessage()
                if (msg is ReadyForQueryMessage) {
                    break
                } else if (msg is ErrorOrNoticeMessage) {
                    // Unguarded for the reason update() gives, and nothing is overwritten here
                    // either: a fetch loop that ended on a server error left only ReadyForQuery
                    // behind it, so an error reaching this loop is one that arrived after the loop
                    // had already broken for a reason of its own - a block that threw.
                    errorResponse = msg
                }
            }

            if (errorResponse != null) throw ExceptionTranslator.translate(errorResponse)
            if (executionError != null) throw executionError

            // A val for the same reason as in update(): capturing the counter itself would box it.
            val streamed = rowCount
            traceDone(startedAt) { "$streamed rows streamed" }
        }
    }
}

