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
 * Runs statements on one connection: the simple protocol for unparameterized SQL, the extended protocol for the
 * rest, with the `BEGIN` a transaction waits on sent ahead of its first statement.
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
        /** The name of every statement and portal: nothing outlives its exchange. */
        const val UNNAMED = ""

        /** Binary for every parameter and column, held so no statement allocates it. */
        val BINARY_FORMAT = listOf(1)
    }

    /** The backend process id every log line carries. */
    private val pid: String get() = "[PID: ${stream.processId}]"

    /** Whether a statement that finds the server idle sends `BEGIN` first; on while auto-commit is off. */
    var beginsTransactions: Boolean = false

    /** What the next `BEGIN` carries - `SET TRANSACTION`, `SET LOCAL` - dropped once it has gone. */
    var deferredTerms: String? = null

    /** Logs a statement at `trace` and returns the clock reading [traceDone] measures from; `0` with tracing off. */
    private fun traceStart(sql: String, params: Array<out Any?>): Long {
        if (!logger.isTraceEnabled()) return 0L
        logger.trace {
            if (sql.isEmpty()) "$pid > (empty query)"
            else "$pid >" + describeParameters(params) + "\n$sql"
        }
        return System.nanoTime()
    }

    /** The parameters of a traced statement: their count, or with [logParameterValues] each `$n` truncated. */
    private fun describeParameters(params: Array<out Any?>): String {
        if (params.isEmpty()) return ""
        if (!logParameterValues) return " (${params.size} params)"
        return params.mapIndexed { index, value -> "\$${index + 1}=${formatDiagnosticValue(value)}" }
            .joinToString(", ", prefix = " (", postfix = ")")
    }

    /** Logs what a successful statement produced and how long it took; [outcome] is built only when logged. */
    private inline fun traceDone(startedAt: Long, crossinline outcome: () -> String) {
        if (startedAt == 0L || !logger.isTraceEnabled()) return
        val elapsedNanos = System.nanoTime() - startedAt
        logger.trace { "$pid < ${outcome()} in ${formatMillis(elapsedNanos)}" }
    }

    /** Milliseconds with three decimals, in [Locale.ROOT] so the separator is always a dot. */
    private fun formatMillis(elapsedNanos: Long): String =
        String.format(Locale.ROOT, "%.3fms", elapsedNanos / 1_000_000.0)

    /** Runs [block] as one exchange, under the lock and marked busy, so a reentrant call is refused. */
    private inline fun <T> exchange(block: () -> T): T = stream.lock.withLock {
        stream.beginExchange()
        try {
            block()
        } finally {
            stream.endExchange()
        }
    }

    /**
     * Writes, without flushing, the `BEGIN` a transaction waits on, so it goes out with the statement. The terms
     * follow in a message of their own: one the server cannot parse then fails the transaction rather than leave
     * the statement to run outside one.
     *
     * @return How many messages went out - none, one, or two with terms - each answered by a `ReadyForQuery`.
     */
    private fun sendWaitingBegin(): Int {
        if (!beginsTransactions || stream.transactionStatus != 'I') return 0
        stream.writeMessage(SimpleQueryMessage("BEGIN"))
        val terms = deferredTerms ?: return 1
        stream.writeMessage(SimpleQueryMessage(terms))
        return 2
    }

    /**
     * Reads the answer to [sendWaitingBegin], ahead of the statement's, and returns the error, if any, for the caller
     * to throw once the statement's own answer is read. [deferredTerms] are dropped once the transaction has begun,
     * and stay for the next try if it has not.
     *
     * @param messages What [sendWaitingBegin] returned.
     */
    private fun receiveBegin(messages: Int): ErrorOrNoticeMessage? {
        if (messages == 0) return null
        var refusal: ErrorOrNoticeMessage? = null
        try {
            var remaining = messages
            while (remaining > 0) {
                when (val msg = stream.receiveMessage()) {
                    is ErrorOrNoticeMessage -> if (refusal == null) refusal = msg
                    is ReadyForQueryMessage -> remaining--
                    else -> { /* The CommandComplete of the BEGIN, and of each term */ }
                }
            }
        } finally {
            if (stream.transactionStatus != 'I') deferredTerms = null
        }
        if (refusal == null) logger.debug { "$pid Transaction begun ahead of its first statement" }
        return refusal
    }

    /** Sends the waiting `BEGIN` now and reads its answer, for a `COPY`, which starts outside an exchange. */
    fun beginWaitingTransactionNow() = exchange {
        val messages = sendWaitingBegin()
        if (messages == 0) return@exchange
        stream.flush()
        receiveBegin(messages)?.let { throw ExceptionTranslator.translate(it) }
    }

    /** Runs [sql] through the simple protocol, dropping any rows; they raise unless [ignoreRows]. */
    fun execute(sql: String, ignoreRows: Boolean = false) = exchange { simpleQuery(sql, ignoreRows, beginFirst = true) }

    /**
     * [execute] without a waiting `BEGIN` first, for what ends a transaction, needs one running, or is about the
     * session: `COMMIT`, `ROLLBACK`, savepoints, session settings, the validation probe.
     */
    fun control(sql: String) = exchange { simpleQuery(sql, ignoreRows = false, beginFirst = false) }

    /** The body of [execute] and [control], inside their exchange. */
    private fun simpleQuery(sql: String, ignoreRows: Boolean, beginFirst: Boolean) {
        val startedAt = traceStart(sql, emptyArray())

        val beginMessages = if (beginFirst) sendWaitingBegin() else 0
        stream.writeMessage(SimpleQueryMessage(sql))
        stream.flush()
        val beginRefusal = receiveBegin(beginMessages)

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
                            "Method execute() received result rows. Use a fetch* method for DQL queries, " +
                                "or execute(ignoreRows = true) for a script whose rows are not wanted."
                        )
                    }
                }
                is CommandCompleteMessage, is EmptyQueryResponseMessage -> { /* Ignore - expected */ }
                else -> { /* Ignore */ }
            }
        }

        // Ahead of the statement's own error, which is only the failed transaction refusing it.
        if (beginRefusal != null) throw ExceptionTranslator.translate(beginRefusal)
        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        traceDone(startedAt) { "done" }
    }

    /**
     * Serializes the parameters, then writes a waiting `BEGIN`, `Parse`, `Bind` and `Describe`; the caller writes
     * the `Execute`s. A parameter that cannot be encoded leaves nothing written.
     *
     * @return What [sendWaitingBegin] returned, for [receiveBegin] once the caller has flushed.
     */
    private fun sendParseBindDescribe(
        sql: String,
        params: Array<out Any?>,
        parameterSerializer: ParameterSerializer?
    ): Int {
        val paramTypes = parameterSerializer?.serializeAll(params, parameterWriter) ?: IntArray(0)
        val paramValues = if (parameterSerializer != null) parameterWriter.data else ByteArray(0)
        val paramValuesLength = if (parameterSerializer != null) parameterWriter.position else 0

        val beginMessages = sendWaitingBegin()
        stream.writeMessage(ParseMessage(UNNAMED, sql, paramTypes))
        stream.writeMessage(
            BindMessage(
                UNNAMED, UNNAMED, params.size, paramValues, BINARY_FORMAT, BINARY_FORMAT, paramValuesLength
            )
        )
        stream.writeMessage(DescribeMessage('P', UNNAMED))
        return beginMessages
    }

    /**
     * Runs a statement that returns no rows and returns how many it changed.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if it returned rows.
     */
    fun executeUpdate(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer? = null
    ): Long = exchange {
        val startedAt = traceStart(sql, params)

        val beginMessages = sendParseBindDescribe(sql, params, parameterSerializer)
        stream.writeMessage(ExecuteMessage(UNNAMED, 0))
        stream.writeMessage(SyncMessage())

        stream.flush()
        val beginRefusal = receiveBegin(beginMessages)

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
                            "Method executeUpdate() received result rows. Use a fetch* method for DQL queries."
                        )
                    }
                }
                // At most one error: the server skips everything up to the Sync after one.
                is ErrorOrNoticeMessage -> errorResponse = msg
                is ReadyForQueryMessage -> break
                else -> { /* Ignore */ }
            }
        }

        // Ahead of the statement's own error, which is only the failed transaction refusing it.
        if (beginRefusal != null) throw ExceptionTranslator.translate(beginRefusal)
        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        // A val: the trace lambda capturing the var would box it on every statement, tracing or not.
        val affected = rowsAffected
        traceDone(startedAt) { "$affected rows affected" }
        return rowsAffected
    }

    /** Runs a query and returns its rows. */
    fun query(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer? = null,
        mapper: ResultMapper,
        maxRows: Int = 0
    ): List<Row> = query(sql, params, parameterSerializer, mapper, maxRows) { it }

    /** Runs a query and returns each row through [transform], at most [maxRows] of them where it is not `0`. */
    fun <R> query(
        sql: String,
        params: Array<out Any?> = emptyArray(),
        parameterSerializer: ParameterSerializer?,
        mapper: ResultMapper,
        maxRows: Int = 0,
        transform: (Row) -> R
    ): List<R> = exchange {
        val startedAt = traceStart(sql, params)

        val beginMessages = sendParseBindDescribe(sql, params, parameterSerializer)
        stream.writeMessage(ExecuteMessage(UNNAMED, maxRows))
        stream.writeMessage(SyncMessage())

        stream.flush()
        val beginRefusal = receiveBegin(beginMessages)

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
                // One Sync, so at most one error.
                is ErrorOrNoticeMessage -> errorResponse = msg
                is ReadyForQueryMessage -> break
                else -> { /* Ignore */ }
            }
        }
        
        if (beginRefusal != null) throw ExceptionTranslator.translate(beginRefusal)
        if (errorResponse != null) {
            throw ExceptionTranslator.translate(errorResponse)
        } else if (executionError != null) {
            throw executionError
        }

        traceDone(startedAt) { "${rows.size} rows" }
        return rows
    }

    /**
     * Runs a query and hands each row through [transform] to [block], fetching [fetchSize] rows at a time, or all
     * of them under `0`.
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
        // Before the lock, so a bad argument does not wait for a running query.
        if (fetchSize < 0) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "fetchSize cannot be negative, was $fetchSize. State how many rows a batch should pull, " +
                "or 0 for the whole result in a single Execute."
        )

        exchange {
            val startedAt = traceStart(sql, params)

            var beginMessages = sendParseBindDescribe(sql, params, parameterSerializer)
            var beginRefusal: ErrorOrNoticeMessage? = null

            var rowMetadata: RowMetadata? = null
            var errorResponse: ErrorOrNoticeMessage? = null
            var executionError: OctaviusException? = null
            // Counted whatever the log level: cheaper than a branch per row.
            var rowCount = 0L

            fetchLoop@ while (true) {
                stream.writeMessage(ExecuteMessage(UNNAMED, fetchSize))
                stream.writeMessage(FlushMessage())
                stream.flush()
                // Only after the first Execute: the BEGIN goes out in the same write as the statement, and a
                // refused term leaves the statement to be refused in turn, which the loop below reads as usual.
                if (beginMessages > 0) {
                    beginRefusal = receiveBegin(beginMessages)
                    beginMessages = 0
                }

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

            stream.writeMessage(SyncMessage())
            stream.flush()
        
            while (true) {
                val msg = stream.receiveMessage()
                if (msg is ReadyForQueryMessage) {
                    break
                } else if (msg is ErrorOrNoticeMessage) {
                    // At most one error here too: one reaching this loop came after a block threw.
                    errorResponse = msg
                }
            }

            if (beginRefusal != null) throw ExceptionTranslator.translate(beginRefusal)
            if (errorResponse != null) throw ExceptionTranslator.translate(errorResponse)
            if (executionError != null) throw executionError

            // A val, as in executeUpdate().
            val streamed = rowCount
            traceDone(startedAt) { "$streamed rows streamed" }
        }
    }
}

