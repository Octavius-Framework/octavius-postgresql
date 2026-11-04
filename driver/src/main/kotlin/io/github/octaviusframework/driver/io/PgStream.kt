package io.github.octaviusframework.driver.io

import io.github.octaviusframework.driver.copy.CopyOperation
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.SessionTerminatedException
import io.github.octaviusframework.driver.message.ServerErrorMessage
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.FrontendMessage
import io.github.octaviusframework.driver.message.frontend.TerminateMessage
import io.github.octaviusframework.driver.message.translator.ExceptionTranslator
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.notice.PgNotice
import io.github.octaviusframework.driver.notification.PgNotification
import io.github.octaviusframework.driver.ssl.SslConfiguration
import io.github.octaviusframework.driver.ssl.SslNegotiator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * One socket to the server, reading and writing protocol messages.
 *
 * @property host The host as written, which a certificate is matched against.
 * @property address The address [host] resolved to that the socket reached, where a cancel request goes too.
 * @property sslConfiguration The TLS settings, which a cancel request is sent under too.
 * @property cancelSignalTimeoutSecs Seconds allowed for a cancel request's connect and reads.
 */
internal class PgStream(
    val host: String,
    val port: Int,
    val address: InetAddress,
    val loginTimeoutSecs: Int = 10,
    notificationBufferCapacity: Int = 256,
    val noticeHandler: NoticeHandler? = null,
    val sslConfiguration: SslConfiguration? = null,
    val cancelSignalTimeoutSecs: Int = 10
) : AutoCloseable {
    companion object {
        private val logger = KotlinLogging.logger {}
        // A logger of its own, so notices can be filtered apart.
        private val noticeLogger = KotlinLogging.logger("io.github.octaviusframework.driver.Notice")

        /** How long a failed write may read what the server sent before closing, for a message that arrived in part. */
        private const val WRITE_FAILURE_READ_TIMEOUT_MS = 1000
    }
    val lock = ReentrantLock()
    private var socket: Socket = Socket()
    var inputStream: PgInputStream
    var outputStream: PgOutputStream
    var processId: Int = -1
    var secretKey: ByteArray = ByteArray(0)
    var isBroken: Boolean = false
    var maxCachedRowSize: Int = 65536

    /** The `FATAL` or `PANIC` the server ended the session with, kept so later failures can say why. */
    var terminatedBy: ServerErrorMessage? = null
        private set

    /**
     * The transfer that last held this connection in copy mode, live or spent; [CopyOperation.isActive] says which.
     * Here because it outlives the session that started it.
     */
    var activeCopy: CopyOperation? = null

    /** Whether a `COPY` holds the connection in copy mode. */
    val copyInProgress: Boolean
        get() = activeCopy?.isActive == true

    /**
     * Whether an exchange runs, from its first message sent until it is drained. Set under [lock], so what it
     * catches is the exchange's own thread: a `forEach` block or a converter running a statement of its own.
     */
    var exchangeInProgress: Boolean = false

    /**
     * Refuses a second exchange or transfer while one runs, which [lock] cannot: it is reentrant, and the thread
     * running a callback is the one that holds it.
     */
    fun checkAvailable() {
        if (copyInProgress) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.CONNECTION_BUSY,
                "A COPY operation is still in progress on this connection. Finish it - endCopy/cancelCopy, or " +
                    "read the export to its end - before using the session for anything else."
            )
        }
        if (exchangeInProgress) {
            throw InvalidOperationException(
                InvalidOperationExceptionReason.CONNECTION_BUSY,
                "A statement is already executing on this connection. This usually means a query was issued " +
                    "from inside a forEach block or a ResultConverter, while the driver was still reading the " +
                    "result - such code needs a separate session."
            )
        }
    }

    /**
     * Starts an exchange, paired with [endExchange] in a `finally`, after [readUnsolicited].
     *
     * @throws SessionTerminatedException if the server ended the session while nobody was reading.
     */
    fun beginExchange() {
        checkAvailable()
        readUnsolicited()
        exchangeInProgress = true
    }

    /** Marks the end of an exchange. */
    fun endExchange() {
        exchangeInProgress = false
    }

    /** The status of the last `ReadyForQuery`: `I` idle, `T` in a transaction, `E` in a failed one. */
    var transactionStatus: Char = 'I'
        private set


    /** Buffers reused for each `DataRow`, which a `Row` decodes as it is built. */
    private var sharedRowData = ByteArray(1024)
    private var sharedColumnOffsets = IntArray(16)
    private var sharedColumnLengths = IntArray(16)

    init {
        val connectTimeoutMs = if (loginTimeoutSecs > 0) loginTimeoutSecs * 1000 else 10000
        socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs
        socket.tcpNoDelay = true
        inputStream = PgInputStream(socket.getInputStream())
        outputStream = PgOutputStream(socket.getOutputStream())
    }

    /** Wraps the socket in TLS, verifying [host] as [config] says. */
    fun upgradeToSSL(host: String, port: Int, config: SslConfiguration) {
        val sslSocket = SslNegotiator.upgrade(socket, host, port, config)
        socket = sslSocket
        inputStream.changeStream(socket.getInputStream())
        outputStream.changeStream(socket.getOutputStream())
    }

    /** Whether the connection ended up encrypted, whatever `sslmode` asked for. */
    val isSecure: Boolean
        get() = socket is SSLSocket

    /** The certificate the server presented, verified or not, which channel binding hashes; `null` in plaintext. */
    val peerCertificate: X509Certificate?
        get() {
            val sslSocket = socket as? SSLSocket ?: return null
            return try {
                sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (_: SSLPeerUnverifiedException) {
                null
            }
        }


    val parameters = mutableMapOf<String, String>()

    /** Whether login reached its first `ReadyForQuery`; a parameter reported after it is logged as a change. */
    var startupComplete: Boolean = false

    /** The socket's read timeout in milliseconds, `0` for none. A dead socket raises a [NetworkException]. */
    var networkTimeout: Int
        get() = try {
            socket.soTimeout
        } catch (e: SocketException) {
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
        }
        set(value) {
            try {
                socket.soTimeout = value
            } catch (e: SocketException) {
                isBroken = true
                throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
            }
        }
    private val _notifications = MutableSharedFlow<PgNotification>(
        extraBufferCapacity = notificationBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val notifications: SharedFlow<PgNotification> = _notifications

    /**
     * Writes [msg] to the buffer.
     *
     * @throws NetworkException if the write fails.
     * @throws SessionTerminatedException if the write failed because the server had ended the session.
     */
    fun sendMessage(msg: FrontendMessage) {
        try {
            msg.encode(outputStream)
        } catch (e: IOException) {
            throw writeFailed(e)
        }
    }

    /**
     * Sends what was written.
     *
     * @throws NetworkException if the write fails.
     * @throws SessionTerminatedException if the write failed because the server had ended the session.
     */
    fun flush() {
        try {
            outputStream.flush()
        } catch (e: IOException) {
            throw writeFailed(e)
        }
    }

    /**
     * What a failed write raises: the `FATAL` the server sent before closing, where it is still there to read, else
     * a `NetworkException`. Over TLS nothing can be read after a failed write.
     */
    private fun writeFailed(e: IOException): OctaviusException {
        isBroken = true
        try {
            socket.soTimeout = WRITE_FAILURE_READ_TIMEOUT_MS
            readUnsolicited()
        } catch (terminated: SessionTerminatedException) {
            terminated.addSuppressed(e)
            return terminated
        } catch (_: Exception) {
            // Nothing to read, or nothing that could be: the failed write is all there is to report.
        }
        return NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
    }

    /**
     * The next message, waiting for it.
     *
     * @throws NetworkException if the read fails.
     * @throws SessionTerminatedException if the message is a `FATAL` or `PANIC` that ended an open session.
     */
    fun receiveMessage(): BackendMessage {
        return receiveMessageInternal(isPolling = false)!!
    }

    /** The next message, or `null` if none starts within the read timeout. */
    fun pollMessage(): BackendMessage? {
        return receiveMessageInternal(isPolling = true)
    }

    /**
     * Reads what the server sent between exchanges, without waiting: notifications, notices, parameters, and a
     * `FATAL` that ended an idle session. Read before the next write, since a write to an exited backend draws a
     * reset, on which Windows discards the `FATAL`. Over TLS only what is already decrypted is seen.
     *
     * @throws SessionTerminatedException if the server ended the session in the meantime.
     */
    fun readUnsolicited() {
        do {
            // Notices and notifications are taken care of by the read itself, a ParameterStatus is recorded by
            // it, and a FATAL raises from inside it. Nothing else is sent unasked.
            val message = receiveMessageInternal(pendingOnly = true)
        } while (message != null)
    }

    /**
     * One message if one has started to arrive, else `null`, for a transfer the server speaks into only to end it.
     *
     * @throws SessionTerminatedException if the server ended the session.
     */
    fun receivePendingMessage(): BackendMessage? = receiveMessageInternal(pendingOnly = true)

    /**
     * Reads one message, handling notices, notifications and parameter reports on the way.
     *
     * @param isPolling Returns `null` on a read timeout before the message starts.
     * @param pendingOnly Returns `null` rather than wait for a message that has not started.
     */
    private fun receiveMessageInternal(isPolling: Boolean = false, pendingOnly: Boolean = false): BackendMessage? {
        var readingTag = true
        try {
            while (true) {
                if (pendingOnly && !inputStream.hasPending()) return null
                readingTag = true
                val tag = inputStream.readByte().toInt().toChar()
                readingTag = false
                val length = inputStream.readInt()
                val payloadLength = length - 4

                when (tag) {
                    'S' -> {
                        val name = inputStream.readCString()
                        val value = inputStream.readCString()
                        val previous = parameters.put(name, value)
                        // Logged only where the value moved; a rollback re-reports parameters unchanged.
                        if (startupComplete && previous != value) {
                            logger.trace {
                                if (previous == null) "[PID: $processId] Session parameter set: $name = $value"
                                else "[PID: $processId] Session parameter changed: $name = $value (was $previous)"
                            }
                        }
                        return ParameterStatusMessage(name, value)
                    }
                    'N' -> {
                        val notice = PgNotice.from(processId, parseErrorOrNotice())

                        when (notice.severity) {
                            "WARNING" -> noticeLogger.warn { "$notice" }
                            "NOTICE", "INFO", "LOG" -> noticeLogger.info { "$notice" }
                            "DEBUG" -> noticeLogger.debug { "$notice" }
                            else -> noticeLogger.info { "$notice" }
                        }

                        if (noticeHandler != null) {
                            try {
                                noticeHandler.handleNotice(notice)
                            } catch (e: Exception) {
                                logger.error(e) { "[PID: $processId] Error in custom NoticeHandler while handling code ${notice.code}" }
                            }
                        }
                    }
                    'A' -> {
                        val pid = inputStream.readInt()
                        val channel = inputStream.readCString()
                        val payload = inputStream.readCString()
                        _notifications.tryEmit(PgNotification(pid, channel, payload))
                    }
                    'R' -> return parseAuthentication(payloadLength)
                    'E' -> {
                        val error = parseErrorOrNotice()
                        if (error.isFatal) endedByServer(error)
                        return error
                    }
                    'v' -> {
                        val newestVersion = inputStream.readInt()
                        val numUnrecognizedOptions = inputStream.readInt()
                        val unrecognizedOptions = mutableListOf<String>()
                        for (i in 0 until numUnrecognizedOptions) {
                            unrecognizedOptions.add(inputStream.readCString())
                        }
                        return NegotiateProtocolVersionMessage(newestVersion, unrecognizedOptions)
                    }
                    'K' -> {
                        val pid = inputStream.readInt()
                        val keyBytes = inputStream.readBytes(payloadLength - 4)
                        return BackendKeyDataMessage(pid, keyBytes)
                    }
                    'Z' -> {
                        val status = inputStream.readByte().toInt().toChar()
                        transactionStatus = status
                        return ReadyForQueryMessage(status)
                    }
                    '1' -> return ParseCompleteMessage
                    '2' -> return BindCompleteMessage
                    'n' -> return NoDataMessage
                    's' -> return PortalSuspendedMessage
                    'I' -> return EmptyQueryResponseMessage
                    'C' -> {
                        val commandTag = inputStream.readCString()
                        return CommandCompleteMessage(commandTag)
                    }
                    'T' -> {
                        val numFields = inputStream.readShort().toInt()
                        val fields = mutableListOf<FieldDescription>()
                        for (i in 0 until numFields) {
                            val fieldName = inputStream.readCString()
                            val tableOid = inputStream.readInt()
                            val columnAttr = inputStream.readShort()
                            val dataTypeOid = inputStream.readInt()
                            val dataTypeSize = inputStream.readShort()
                            val typeModifier = inputStream.readInt()
                            val formatCode = inputStream.readShort()
                            fields.add(
                                FieldDescription(
                                    fieldName, tableOid, columnAttr, dataTypeOid, dataTypeSize, typeModifier, formatCode
                                )
                            )
                        }
                        return RowDescriptionMessage(fields)
                    }
                    'D' -> {
                        val numColumns = inputStream.readShort().toInt()
                        val rowDataSize = payloadLength - 2
                        
                        val rowDataBuffer: ByteArray
                        if (rowDataSize > maxCachedRowSize) { // Don't cache arrays larger than maxCachedRowSize to avoid memory leaks on huge values
                            rowDataBuffer = ByteArray(rowDataSize)
                        } else {
                            if (sharedRowData.size < rowDataSize) {
                                var newSize = sharedRowData.size * 2
                                while (newSize < rowDataSize) newSize *= 2
                                sharedRowData = ByteArray(newSize)
                            }
                            rowDataBuffer = sharedRowData
                        }

                        if (sharedColumnOffsets.size < numColumns) {
                            var newSize = sharedColumnOffsets.size * 2
                            while (newSize < numColumns) newSize *= 2
                            sharedColumnOffsets = IntArray(newSize)
                            sharedColumnLengths = IntArray(newSize)
                        }

                        inputStream.readFully(rowDataBuffer, rowDataSize)

                        var offset = 0
                        for (i in 0 until numColumns) {
                            val colLength = rowDataBuffer.getIntBE(offset)
                            offset += 4
                            sharedColumnLengths[i] = colLength
                            if (colLength == -1) {
                                sharedColumnOffsets[i] = -1
                            } else {
                                sharedColumnOffsets[i] = offset
                                offset += colLength
                            }
                        }
                        return DataRowMessage(rowDataBuffer, sharedColumnOffsets, sharedColumnLengths)
                    }
                    'G' -> {
                        val format = inputStream.readByte()
                        val numColumns = inputStream.readShort()
                        val columnFormats = mutableListOf<Short>()
                        for (i in 0 until numColumns) {
                            columnFormats.add(inputStream.readShort())
                        }
                        return CopyInResponseMessage(format, numColumns, columnFormats)
                    }
                    'H' -> {
                        val format = inputStream.readByte()
                        val numColumns = inputStream.readShort()
                        val columnFormats = mutableListOf<Short>()
                        for (i in 0 until numColumns) {
                            columnFormats.add(inputStream.readShort())
                        }
                        return CopyOutResponseMessage(format, numColumns, columnFormats)
                    }
                    'W' -> {
                        val format = inputStream.readByte()
                        val numColumns = inputStream.readShort()
                        val columnFormats = mutableListOf<Short>()
                        for (i in 0 until numColumns) {
                            columnFormats.add(inputStream.readShort())
                        }
                        return CopyBothResponseMessage(format, numColumns, columnFormats)
                    }
                    'd' -> {
                        val data = inputStream.readBytes(payloadLength)
                        return BackendCopyDataMessage(data)
                    }
                    'c' -> return BackendCopyDoneMessage
                    else -> {
                        inputStream.skip(payloadLength)
                        logger.trace { "IGNORING: Unsupported synchronous message type: $tag" }
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            if (isPolling && readingTag) {
                return null
            }
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_TIMEOUT, cause = e)
        } catch (e: EOFException) {
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_CLOSED_BY_PEER, cause = e)
        } catch (e: IOException) {
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
        }
    }

    /**
     * Parses an authentication request.
     *
     * @throws InitializationException if the mechanism is unsupported.
     */
    private fun parseAuthentication(payloadLength: Int): BackendMessage {
        return when (val type = inputStream.readInt()) {
            0 -> AuthenticationMessage.Ok
            3 -> AuthenticationMessage.CleartextPassword
            5 -> {
                val salt = inputStream.readBytes(4)
                AuthenticationMessage.MD5Password(salt)
            }
            10 -> {
                val mechanisms = mutableListOf<String>()
                while (true) {
                    val mech = inputStream.readCString()
                    if (mech.isEmpty()) break
                    mechanisms.add(mech)
                }
                AuthenticationMessage.SASL(mechanisms)
            }
            11 -> {
                val data = inputStream.readBytes(payloadLength - 4)
                AuthenticationMessage.SASLContinue(data)
            }
            12 -> {
                val data = inputStream.readBytes(payloadLength - 4)
                AuthenticationMessage.SASLFinal(data)
            }
            else -> throw InitializationException(
                InitializationExceptionReason.UNSUPPORTED_MECHANISM,
                details = "Unknown authentication type: $type"
            )
        }
    }

    /** Parses the fields an `ErrorResponse` and a `NoticeResponse` share. */
    private fun parseErrorOrNotice(): ErrorOrNoticeMessage {
        val fields = mutableMapOf<Char, String>()
        while (true) {
            val token = inputStream.readByte().toInt().toChar()
            if (token == '\u0000') break
            val value = inputStream.readCString()
            fields[token] = value
        }
        return ErrorOrNoticeMessage(fields)
    }

    /**
     * Gives the connection up on a `FATAL` or `PANIC`, which only the close follows, and raises it as
     * [SessionTerminatedException]. During login it returns, and the handshake reports it as a refusal.
     */
    private fun endedByServer(error: ErrorOrNoticeMessage) {
        isBroken = true
        terminatedBy = ServerErrorMessage.from(error)
        dropSocket()
        if (startupComplete) throw ExceptionTranslator.translateTermination(error)
    }

    /**
     * Waits, within the read timeout, for the server to close this connection, which is how it answers a cancel
     * request. Never throws.
     */
    fun awaitServerClose() {
        try {
            val raw = socket.getInputStream()
            @Suppress("ControlFlowWithEmptyBody")
            while (raw.read() != -1) {
            }
        } catch (_: Exception) {
            // A timeout, a reset or a closed socket all mean nothing more is coming.
        }
    }

    /** Closes the socket without the Terminate [close] sends, for a connection with no session behind it. */
    fun dropSocket() {
        if (!socket.isClosed) {
            try {
                socket.close()
            } catch (e: Exception) {
                logger.trace(e) { "[PID: $processId] Failed to close socket" }
            }
        }
    }

    /** Sends Terminate, if the socket is open, and closes it. */
    override fun close() {
        if (!socket.isClosed) {
            try {
                // Not through sendMessage, whose failed write reads: an abort runs here while another thread may
                // be reading.
                TerminateMessage().encode(outputStream)
                outputStream.flush()
            } catch (e: Exception) {
                // Ignoring errors during close
                logger.trace(e) { "[PID: $processId] Failed to send Terminate before closing" }
            }
            try {
                socket.close()
            } catch (e: Exception) {
                logger.trace(e) { "[PID: $processId] Failed to close socket" }
            }
            logger.debug { "[PID: $processId] Connection to $host:$port closed" }
        }
    }
}

