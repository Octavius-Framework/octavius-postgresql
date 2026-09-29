package io.github.octaviusframework.driver.io

import io.github.octaviusframework.driver.copy.CopyOperation
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.message.backend.*
import io.github.octaviusframework.driver.message.frontend.FrontendMessage
import io.github.octaviusframework.driver.message.frontend.TerminateMessage
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Represents a connection stream to a PostgreSQL database.
 * Handles reading and writing of PostgreSQL wire protocol messages.
 *
 * @property host The hostname or IP address of the PostgreSQL server.
 * @property port The port number of the PostgreSQL server.
 * @property loginTimeoutSecs Timeout in seconds for the initial connection and login process.
 * @param notificationBufferCapacity Capacity of the buffer for asynchronous notifications.
 * @property sslConfiguration The SSL settings this connection was established with, kept so that a
 *   second connection opened on its behalf - a cancel request - can be given the same treatment.
 * @property cancelSignalTimeoutSecs Seconds allowed for a cancel request sent on this connection's
 *   behalf, covering both its connect and its reads. Carried here for the same reason.
 */
internal class PgStream(
    val host: String,
    val port: Int,
    val loginTimeoutSecs: Int = 10,
    notificationBufferCapacity: Int = 256,
    val noticeHandler: NoticeHandler? = null,
    val sslConfiguration: SslConfiguration? = null,
    val cancelSignalTimeoutSecs: Int = 10
) : AutoCloseable {
    companion object {
        private val logger = KotlinLogging.logger {}
        // A specific logger name allows users to filter just notices in logback.xml
        private val noticeLogger = KotlinLogging.logger("io.github.octaviusframework.driver.Notice")
    }
    val lock = ReentrantLock()
    private var socket: Socket = Socket()
    var inputStream: PgInputStream
    var outputStream: PgOutputStream
    var processId: Int = -1
    var secretKey: ByteArray = ByteArray(0)
    var isBroken: Boolean = false
    var maxCachedRowSize: Int = 65536

    /**
     * The transfer that last held this connection in copy mode, live or spent.
     *
     * On the stream rather than on the `CopyManager` or `Session` that started it, because it outlives
     * every one of them: a manager belongs to one session, and several sessions can be opened
     * over one connection in turn, each with a manager of its own.
     *
     * Set by the operations under [lock] and never cleared - [CopyOperation.isActive] is the
     * authority on whether it still refers to anything live.
     */
    var activeCopy: CopyOperation? = null

    /**
     * True while a COPY operation holds the connection in copy mode.
     *
     * Read through [activeCopy], which the operations maintain under [lock], so others only ever
     * observe it while the lock is free - at which point it says what is actually in flight.
     */
    val copyInProgress: Boolean
        get() = activeCopy?.isActive == true

    /**
     * True while a statement is being executed on this connection, from the first message sent
     * until the exchange is fully drained.
     *
     * Set by [beginExchange] and cleared by [endExchange], both under [lock], so it is only
     * observed by others while the lock is free - at which point it is always false. What it
     * really catches is *reentrant* use: code called back into during an exchange (a `forEach`
     * block, a `ResultConverter`) trying to run its own statement on the same connection.
     */
    var exchangeInProgress: Boolean = false

    /**
     * Guards any operation that needs the protocol stream to itself.
     *
     * A connection can carry exactly one exchange at a time. Anything interleaved into a
     * transfer or a result being read would consume the other's messages and desynchronize
     * the connection, so this fails fast instead. Note that [lock] cannot catch this on its
     * own - it is reentrant, so the very thread that owns the exchange passes straight
     * through it, and that is precisely the thread a callback runs on.
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
     * Marks the start of an exchange, refusing to begin one while another is already running.
     * Every call must be paired with [endExchange] in a `finally`.
     */
    fun beginExchange() {
        checkAvailable()
        exchangeInProgress = true
    }

    /** Marks the end of an exchange. */
    fun endExchange() {
        exchangeInProgress = false
    }

    /**
     * The transaction status the server reported on its last `ReadyForQuery`: `I` outside a
     * transaction block, `T` inside one, `E` inside one that has failed.
     */
    var transactionStatus: Char = 'I'
        private set


    /**
     * Shared buffers used to reduce memory allocations during 'DataRow' deserialization.
     * Since 'Row' eagerly parses all values into basic JVM types during initialization,
     * the underlying byte arrays can be immediately reused for the next row.
     */
    private var sharedRowData = ByteArray(1024)
    private var sharedColumnOffsets = IntArray(16)
    private var sharedColumnLengths = IntArray(16)

    init {
        val connectTimeoutMs = if (loginTimeoutSecs > 0) loginTimeoutSecs * 1000 else 10000
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = connectTimeoutMs
        socket.tcpNoDelay = true
        inputStream = PgInputStream(socket.getInputStream())
        outputStream = PgOutputStream(socket.getOutputStream())
    }

    /**
     * Upgrades the current connection to use SSL/TLS.
     *
     * @param host The hostname to verify against the SSL certificate.
     * @param port The port number.
     * @param config The SSL configuration to use.
     */
    fun upgradeToSSL(host: String, port: Int, config: SslConfiguration) {
        val sslSocket = SslNegotiator.upgrade(socket, host, port, config)
        socket = sslSocket
        inputStream.changeStream(socket.getInputStream())
        outputStream.changeStream(socket.getOutputStream())
    }

    /** Whether the connection ended up encrypted, whatever `sslmode` asked for. */
    val isSecure: Boolean
        get() = socket is SSLSocket

    /**
     * The certificate the server presented during the TLS handshake, or null on a plaintext
     * connection and on the rare TLS connection where the peer never authenticated itself.
     *
     * Nothing here judges the certificate - that is the trust manager's job during
     * [upgradeToSSL], and under `sslmode=require` it deliberately accepts anything. This
     * reports only what was presented, which is precisely what channel binding hashes: the
     * proof then covers the certificate actually on the wire, verified or not.
     */
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

    /**
     * False until the startup handshake reaches its first `ReadyForQuery`.
     *
     * The parameters that arrive during login come in one burst and are reported as a single line
     * by the authenticator. Only what changes *after* that is an event of its own, and this is what
     * tells the two apart.
     */
    var startupComplete: Boolean = false

    /**
     * The socket's read timeout, in milliseconds, with 0 meaning no limit.
     *
     * Both directions are socket operations and both fail on a dead socket, so they are wrapped
     * the way [sendMessage] and [flush] are. Left raw, a `SocketException` would travel out of
     * `Connection.setNetworkTimeout` and `getNetworkTimeout` - which JDBC declares as throwing
     * `SQLException`, and which a pool therefore only guards against as one.
     */
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
     * Sends a frontend message to the PostgreSQL server.
     *
     * @param msg The frontend message to send.
     * @throws NetworkException if a network error occurs during transmission.
     */
    fun sendMessage(msg: FrontendMessage) {
        try {
            msg.encode(outputStream)
        } catch (e: IOException) {
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
        }
    }

    /**
     * Flushes the underlying output stream, forcing any buffered bytes to be written out.
     *
     * @throws NetworkException if a network error occurs during flush.
     */
    fun flush() {
        try {
            outputStream.flush()
        } catch (e: IOException) {
            isBroken = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, cause = e)
        }
    }

    /**
     * Receives a single backend message synchronously, blocking until data is available.
     *
     * @return The parsed backend message.
     * @throws NetworkException if a network error occurs.
     */
    fun receiveMessage(): BackendMessage {
        return receiveMessageInternal(isPolling = false)!!
    }

    /**
     * Attempts to receive a backend message if one is immediately available or within the timeout.
     * Returns null if no message is available (e.g. timeout reached while polling).
     *
     * @return The parsed backend message, or null if a timeout occurs before receiving the tag.
     */
    fun pollMessage(): BackendMessage? {
        return receiveMessageInternal(isPolling = true)
    }

    /**
     * Internal function to receive a backend message, optionally handling timeouts gracefully if polling.
     *
     * @param isPolling If true, a read timeout when reading the initial message tag will return null instead of throwing an exception.
     * @return The parsed backend message, or null if polling and a timeout occurs.
     */
    private fun receiveMessageInternal(isPolling: Boolean = false): BackendMessage? {
        var readingTag = true
        try {
            while (true) {
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
                        // A GUC_REPORT parameter moving mid-session is a real event, and often an
                        // invisible one: a hand-written `SET search_path` changes which types and
                        // tables everything after it resolves against, with nothing else to show
                        // for it. Only actual changes - the server re-reports a parameter on
                        // rollback whether or not the value moved.
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
                    'E' -> return parseErrorOrNotice()
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
     * Parses an authentication request message from the backend.
     *
     * @param payloadLength The length of the message payload.
     * @return The parsed AuthenticationMessage.
     * @throws InitializationException if the authentication mechanism is unsupported.
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

    /**
     * Parses the field set an ErrorResponse (Tag 'E') and a NoticeResponse (Tag 'N') share.
     *
     * The fields terminate themselves with a zero byte, so the payload length says nothing this
     * needs and is not taken.
     *
     * @return The parsed [ErrorOrNoticeMessage].
     */
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
     * Blocks until the server closes this connection, discarding anything it sends beforehand.
     *
     * A backend answers a CancelRequest by closing the connection rather than replying, so waiting
     * for that is what confirms the request was read - instead of leaving it in a socket buffer
     * that an immediate close would race with. The socket timeout the stream was opened with
     * bounds the wait, so a server that accepts the connection and then goes quiet cannot park
     * the caller here.
     *
     * Never throws and reports nothing: how the connection ends carries no usable signal, since a
     * backend that acted on the request drops it rather than closing it cleanly.
     */
    fun awaitServerClose() {
        try {
            val raw = socket.getInputStream()
            @Suppress("ControlFlowWithEmptyBody")
            while (raw.read() != -1) {
            }
        } catch (_: Exception) {
            // A timeout, a reset, an already-closed socket: all say the same thing here, which is
            // that nothing more is coming.
        }
    }

    /**
     * Closes the socket without the Terminate that [close] sends first.
     *
     * For a connection the server is expected to hang up on by itself - a cancel request - a
     * Terminate is only bytes written at a backend that has already gone.
     */
    fun dropSocket() {
        if (!socket.isClosed) {
            try {
                socket.close()
            } catch (e: Exception) {
                logger.trace(e) { "[PID: $processId] Failed to close cancel socket" }
            }
        }
    }

    /**
     * Closes the connection stream, sending a Terminate message if the socket is still open.
     */
    override fun close() {
        if (!socket.isClosed) {
            try {
                sendMessage(TerminateMessage())
                flush()
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

