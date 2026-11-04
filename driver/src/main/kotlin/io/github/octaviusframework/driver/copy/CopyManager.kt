package io.github.octaviusframework.driver.copy

import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.exception.ConstraintViolationException
import io.github.octaviusframework.driver.exception.DataException
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.FrontendCopyDataMessage
import io.github.octaviusframework.driver.message.frontend.FrontendCopyDoneMessage
import io.github.octaviusframework.driver.message.frontend.FrontendCopyFailMessage
import io.github.octaviusframework.driver.message.frontend.SimpleQueryMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Runs `COPY … FROM STDIN` and `COPY … TO STDOUT` on this session. With auto-commit off, a `COPY` begins the
 * transaction like any other statement.
 */
class CopyManager internal constructor(
    private val stream: PgStream,
    private val queryExecutor: QueryExecutor
) {

    companion object {
        /** Chunk size of the [InputStream] overload of [copyIn]. */
        const val DEFAULT_BUFFER_SIZE: Int = 65536
    }

    /**
     * Starts [sql], a `COPY … FROM STDIN`, and returns the handle to write it through. The connection runs nothing
     * else until the transfer ends; closing the handle cancels one still running.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if [sql] did not start a COPY IN.
     */
    fun copyIn(sql: String): CopyIn {
        stream.lock.lock()
        try {
            stream.checkAvailable()
            queryExecutor.beginWaitingTransactionNow()
            stream.sendMessage(SimpleQueryMessage(sql))
            stream.flush()

            var errorResponse: ErrorOrNoticeMessage? = null
            while (true) {
                when (val msg = stream.receiveMessage()) {
                    is ErrorOrNoticeMessage -> errorResponse = msg
                    is CopyInResponseMessage -> {
                        logger.debug { "[PID: ${stream.processId}] COPY IN started:\n$sql" }
                        return CopyIn(stream)
                    }
                    is ReadyForQueryMessage -> {
                        if (errorResponse != null) {
                            throw ExceptionTranslator.translate(errorResponse)
                        }
                        throw InvalidOperationException(
                            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                            "Query did not initiate a COPY IN operation."
                        )
                    }
                    else -> { /* Ignore */ }
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }

    /**
     * Starts [sql], a `COPY … TO STDOUT`, and returns the handle to read it through. The connection runs nothing
     * else until the transfer ends; closing the handle cancels one still running.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if [sql] did not start a COPY OUT.
     */
    fun copyOut(sql: String): CopyOut {
        stream.lock.lock()
        try {
            stream.checkAvailable()
            queryExecutor.beginWaitingTransactionNow()
            stream.sendMessage(SimpleQueryMessage(sql))
            stream.flush()

            var errorResponse: ErrorOrNoticeMessage? = null
            while (true) {
                val msg = stream.receiveMessage()
                when (msg) {
                    is ErrorOrNoticeMessage -> errorResponse = msg
                    is CopyOutResponseMessage -> {
                        logger.debug { "[PID: ${stream.processId}] COPY OUT started:\n$sql" }
                        return CopyOut(stream)
                    }
                    is ReadyForQueryMessage -> {
                        if (errorResponse != null) {
                            throw ExceptionTranslator.translate(errorResponse)
                        }
                        throw InvalidOperationException(
                            InvalidOperationExceptionReason.UNEXPECTED_RESULT,
                            "Query did not initiate a COPY OUT operation."
                        )
                    }
                    else -> { /* Ignore */ }
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }

    /**
     * Runs [sql], a `COPY … FROM STDIN`, over [inputStream] and returns the number of rows the server accepted.
     * On any failure the transfer is cancelled, and a row the server rejects stops the reading at the next
     * chunk. [inputStream] is left open.
     *
     * @param bufferSize Bytes per chunk; each chunk is one message and one flush.
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [bufferSize] is not positive,
     *   `UNEXPECTED_RESULT` if [sql] did not start a COPY IN.
     */
    fun copyIn(sql: String, inputStream: InputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        if (bufferSize <= 0) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                "Copy buffer size must be positive, was $bufferSize."
            )
        }
        val copyIn = copyIn(sql)
        try {
            val buffer = ByteArray(bufferSize)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                copyIn.writeToCopy(buffer, 0, bytesRead)
            }
            return copyIn.endCopy()
        } catch (e: Exception) {
            if (copyIn.isActive) {
                copyIn.cancelCopy()
            }
            throw e
        }
    }

    /**
     * Runs [sql], a `COPY … TO STDOUT`, into [outputStream] and returns the number of bytes written - not rows,
     * which the server does not report. On any failure the transfer is cancelled. [outputStream] is neither
     * flushed nor closed.
     *
     * @throws InvalidOperationException `UNEXPECTED_RESULT` if [sql] did not start a COPY OUT.
     */
    fun copyOut(sql: String, outputStream: OutputStream): Long {
        val copyOut = copyOut(sql)
        var totalBytes = 0L
        try {
            while (true) {
                val chunk = copyOut.readFromCopy() ?: break
                outputStream.write(chunk)
                totalBytes += chunk.size
            }
            return totalBytes
        } catch (e: Exception) {
            if (copyOut.isActive) {
                copyOut.cancelCopy()
            }
            throw e
        }
    }
}

/**
 * A `COPY … FROM STDIN` in progress: write chunks with [writeToCopy], finish with [endCopy]. Single-use.
 *
 * What the server checks as it reads a row - parsing, column count, `NOT NULL`, `CHECK` - ends the copy, and
 * the next [writeToCopy] raises it. Foreign keys, and on a small copy duplicate keys, are raised by [endCopy].
 */
class CopyIn internal constructor(private val stream: PgStream) : CopyOperation {
    override var isActive: Boolean = true
        private set

    /** Registers the transfer on the connection. */
    init {
        stream.activeCopy = this
    }

    /**
     * Sends [length] bytes of [data] from [offset] as one message and one flush, so chunks should be larger than
     * a row. A row the server already rejected, or a session it ended, is raised instead of sending, and the
     * operation is finished.
     *
     * @throws InvalidOperationException `RESOURCE_CLOSED` if the operation has already finished.
     * @throws DataException if the server rejected a row it could not parse.
     * @throws ConstraintViolationException if the server rejected a row that breaks a constraint.
     * @throws SessionTerminatedException if the server ended the session.
     */
    fun writeToCopy(data: ByteArray, offset: Int = 0, length: Int = data.size) = stream.lock.withLock {
        if (!isActive) throw InvalidOperationException(InvalidOperationExceptionReason.RESOURCE_CLOSED, "This COPY operation is no longer active. Handles are single-use - start a new one through the CopyManager.")
        raiseIfServerEnded()
        stream.sendMessage(FrontendCopyDataMessage(data, offset, length))
        stream.flush()
    }

    /**
     * Raises the error the server ended the copy with, if it has sent one, after reading the `ReadyForQuery` that
     * follows it. A `FATAL` raises from inside the read.
     */
    private fun raiseIfServerEnded() {
        while (true) {
            val msg = stream.receivePendingMessage() ?: return
            if (msg is ErrorOrNoticeMessage) {
                do {
                    val next = stream.receiveMessage()
                } while (next !is ReadyForQueryMessage)
                isActive = false
                throw ExceptionTranslator.translate(msg)
            }
        }
    }

    /**
     * Finishes the copy and returns the number of rows the server accepted, or raises what it rejected after the
     * last chunk. The connection leaves copy mode either way.
     *
     * @throws InvalidOperationException `RESOURCE_CLOSED` if the operation has already finished.
     */
    fun endCopy(): Long {
        stream.lock.lock()
        try {
            if (!isActive) throw InvalidOperationException(InvalidOperationExceptionReason.RESOURCE_CLOSED, "This COPY operation is no longer active. Handles are single-use - start a new one through the CopyManager.")
            stream.sendMessage(FrontendCopyDoneMessage())
            stream.flush()

            var rowsAffected = 0L
            var errorResponse: ErrorOrNoticeMessage? = null
            while (true) {
                val msg = stream.receiveMessage()
                when (msg) {
                    is ErrorOrNoticeMessage -> errorResponse = msg
                    is CommandCompleteMessage -> {
                        val parts = msg.tag.split(" ")
                        if (parts.size >= 2) {
                            rowsAffected = parts.last().toLongOrNull() ?: 0L
                        }
                    }
                    is ReadyForQueryMessage -> {
                        isActive = false
                        if (errorResponse != null) {
                            throw ExceptionTranslator.translate(errorResponse)
                        }
                        logger.debug { "[PID: ${stream.processId}] COPY IN finished: $rowsAffected rows" }
                        return rowsAffected
                    }
                    else -> { /* Ignore */ }
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }

    override fun cancelCopy() {
        stream.lock.lock()
        try {
            if (!isActive) return
            if (stream.isBroken) {
                // The server discarded the transfer with the connection; trying would only raise about it.
                isActive = false
                return
            }
            stream.sendMessage(FrontendCopyFailMessage("Copy operation cancelled by user."))
            stream.flush()
            isActive = false
            logger.debug { "[PID: ${stream.processId}] COPY IN cancelled" }
            
            // Discard messages until ReadyForQuery
            while (true) {
                val msg = stream.receiveMessage()
                if (msg is ReadyForQueryMessage) {
                    break
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }
}

/**
 * A `COPY … TO STDOUT` in progress: call [readFromCopy] until it returns `null`, or cancel. Single-use.
 */
class CopyOut internal constructor(private val stream: PgStream) : CopyOperation {
    override var isActive: Boolean = true
        private set

    private var errorResponse: ErrorOrNoticeMessage? = null

    /** For the log line that closes the transfer. */
    private var bytesRead: Long = 0L

    /** Registers the transfer on the connection. */
    init {
        stream.activeCopy = this
    }

    /**
     * The next chunk, or `null` once the transfer is over and the connection has left copy mode. A chunk is one
     * message from the server, not one row.
     */
    fun readFromCopy(): ByteArray? {
        stream.lock.lock()
        try {
            if (!isActive) return null

            while (true) {
                val msg = stream.receiveMessage()
                when (msg) {
                    is BackendCopyDataMessage -> {
                        bytesRead += msg.data.size
                        return msg.data
                    }
                    is BackendCopyDoneMessage -> {
                        // Expect CommandComplete and ReadyForQuery
                    }
                    is ErrorOrNoticeMessage -> {
                        errorResponse = msg
                    }
                    is CommandCompleteMessage -> { /* Ignore */ }
                    is ReadyForQueryMessage -> {
                        isActive = false
                        if (errorResponse != null) {
                            throw ExceptionTranslator.translate(errorResponse!!)
                        }
                        logger.debug { "[PID: ${stream.processId}] COPY OUT finished: $bytesRead bytes" }
                        return null
                    }
                    else -> { /* Ignore */ }
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }

    override fun cancelCopy() {
        stream.lock.lock()
        try {
            if (!isActive) return
            isActive = false
            // Nothing left to drain on a connection that is gone; see CopyIn.cancelCopy.
            if (stream.isBroken) return
            logger.debug { "[PID: ${stream.processId}] COPY OUT cancelled" }
            // Wait for ReadyForQuery
            while (true) {
                val msg = stream.receiveMessage()
                if (msg is ReadyForQueryMessage) {
                    break
                }
            }
        } finally {
            stream.lock.unlock()
        }
    }
}
