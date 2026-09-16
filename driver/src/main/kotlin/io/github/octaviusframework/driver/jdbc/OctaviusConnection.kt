package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.*
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.CancelRequestMessage
import io.github.octaviusframework.driver.parser.SqlParameterParser
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.session.TransactionState
import io.github.octaviusframework.driver.ssl.SslNegotiator
import io.github.octaviusframework.driver.transaction.OctaviusSavepointImpl
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.*
import java.util.*
import java.util.concurrent.Executor
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

/**
 * Represents a connection to a database within the Octavius Framework.
 * It implements the standard JDBC [Connection] interface but overrides
 * certain behaviors to fit the framework's architecture.
 */
internal class OctaviusConnection(
    internal val stream: PgStream,
    internal val databaseKey: DatabaseKey,
    maxParameterWriterCapacity: Int?,
    initialParameterWriterCapacity: Int?,
    logParameterValues: Boolean
) : Connection {
    val catalogHolder = GlobalCatalogStore.holderFor(databaseKey)

    val queryExecutor = QueryExecutor(
        stream,
        maxParameterWriterCapacity,
        initialParameterWriterCapacity,
        logParameterValues
    )
    init {
        GlobalCatalogStore.ensureLoaded(databaseKey, queryExecutor)
    }

    @Volatile
    var isClosedFlag: Boolean = false

    private inline fun <T> wrapSqlException(block: () -> T): T {
        try {
            return block()
        } catch (e: OctaviusException) {
            throw SQLExceptionWrapper(e)
        }
    }

    private var lastSearchPathString: String? = null
    private var cachedSearchPath: List<String>? = null


    /**
     * Prefix every log line on this connection carries.
     *
     * The backend process id is what ties a driver log to `pg_stat_activity` and to the server's
     * own log, which is the only way to follow one connection across all three.
     */
    private val pid: String get() = "[PID: ${stream.processId}]"

    /**
     * Raises unless this connection is still usable, checking both what was asked of it and what happened to
     * it — a connection closed by the caller and one whose stream broke underneath are different failures and
     * are reported as different SQLSTATEs.
     *
     * Finding a broken stream also latches the closed flag, so the second call answers from the flag rather
     * than by asking a socket that is already gone.
     *
     * @throws NetworkException `CONNECTION_CLOSED` (`08003`) if it was closed, `CONNECTION_ERROR` (`08006`)
     *   if the stream broke.
     */
    fun checkClosed() {
        if (isClosedFlag) throw NetworkException(NetworkExceptionReason.CONNECTION_CLOSED, sqlState = "08003")
        if (stream.isBroken) {
            isClosedFlag = true
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, details = "Underlying stream is broken", sqlState = "08006")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) {
            return this as T
        }
        throw InvalidOperationException(InvalidOperationExceptionReason.UNWRAP_ERROR, details = "Cannot unwrap to ${iface.name}")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    override fun nativeSQL(sql: String?): String = sql?.let { SqlParameterParser.parse(sql).transformedSql } ?: ""

    override fun close() {
        if (!isClosedFlag) {
            isClosedFlag = true
            stream.close()
        }
    }

    override fun isClosed(): Boolean {
        if (stream.isBroken) isClosedFlag = true
        return isClosedFlag
    }

    override fun getMetaData(): DatabaseMetaData = unsupported()

    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {} // required by Hikari


    override fun isValid(timeout: Int): Boolean { // required by Hikari
        if (timeout < 0) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Timeout for isValid() cannot be negative, was $timeout"
        )
        if (isClosed()) return false

        // The timeout is a property of the whole connection, so it is swapped under the same lock
        // that serializes exchanges - otherwise this would shorten the deadline of a query already
        // in flight on another thread, and kill a healthy connection with a read timeout.
        return stream.lock.withLock {
            val originalTimeout = try {
                stream.networkTimeout
            } catch (e: NetworkException) {
                logger.debug(e) { "$pid Could not read the network timeout; reporting the connection as invalid" }
                return@withLock false
            }
            // In JDBC, the timeout for isValid is in seconds (0 means no limit). Widened to Long
            // first, so a large value saturates instead of overflowing into a negative timeout.
            val requested = (timeout.toLong() * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Only ever tighten the deadline. Relaxing it would let a probe outlive the timeout the
            // connection was configured with - and isValid(0) would drop that timeout altogether,
            // leaving the check to hang forever on exactly the dead socket it was meant to detect.
            val tightens = requested != 0 && (originalTimeout == 0 || requested < originalTimeout)

            try {
                if (tightens) stream.networkTimeout = requested
                queryExecutor.execute("")
                true
            } catch (e: InvalidOperationException) {
                // Misuse, not ill health: this very thread already owns an exchange or a COPY on
                // this connection, which is only reachable from inside a streaming block or a
                // converter. The connection is fine, so answering "invalid" would bury a caller
                // bug under a wrong answer - and lose the reason enum that names it.
                throw e
            } catch (e: Exception) {
                logger.debug(e) { "$pid Validation probe failed; reporting the connection as invalid" }
                false
            } finally {
                if (tightens) {
                    try {
                        stream.networkTimeout = originalTimeout
                    } catch (e: Exception) {
                        logger.debug(e) { "$pid Failed to restore network timeout after validation" }
                    }
                }
            }
        }
    }

    override fun setClientInfo(name: String?, value: String?) = unsupported()
    override fun setClientInfo(properties: Properties?) = unsupported()
    override fun getClientInfo(name: String?): String = unsupported()
    override fun getClientInfo(): Properties = Properties()


    override fun abort(executor: Executor?) {
        if (executor == null) throw InvalidOperationException(
            InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED,
            details = "Executor cannot be null"
        )
        
        if (isClosedFlag) return
        isClosedFlag = true

        logger.debug { "$pid Connection aborted" }

        executor.execute {
            stream.close()
        }
        // Signal for Hikari to evict Connection
        throw SQLExceptionWrapper(NetworkException(
            NetworkExceptionReason.CONNECTION_ABORTED,
            details = "Connection explicitly aborted by Octavius",
            sqlState = "08000"
        ))
    }

    override fun setNetworkTimeout(executor: Executor?, milliseconds: Int) = wrapSqlException { // required by Hikari
        checkClosed()
        if (milliseconds < 0) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Network timeout cannot be negative, was $milliseconds"
        )
        stream.networkTimeout = milliseconds
    }

    override fun getNetworkTimeout(): Int = wrapSqlException { // required by Hikari
        checkClosed()
        return@wrapSqlException stream.networkTimeout
    }

    /**
     * Asks the server to abandon whatever this connection is currently running.
     *
     * A request, not an instruction: PostgreSQL may have finished, or may be somewhere it does not check for
     * one, and nothing here waits to find out. It travels on a **second connection** because the protocol
     * requires that — this one is busy being the thing to cancel — which is why it can be called while a
     * query is in flight and why a firewall that permits the session may still not permit this.
     *
     * Failing to open that second connection is not reported: there was nothing to clean up and the query
     * carries on. A cancelled query surfaces at whoever is running it, as
     * [ExecutionAbortedException][io.github.octaviusframework.driver.exception.ExecutionAbortedException]
     * (`QUERY_CANCELED`, SQLSTATE `57014`) — the same one a `statement_timeout` produces, since the server
     * reports both the same way.
     *
     * @throws NetworkException if this connection is already closed or broken.
     */
    fun cancelQuery() {
        checkClosed()

        // The cancel request cannot travel on this connection - the protocol requires a fresh one -
        // and it carries the backend's process id and cancel key. cancelSignalTimeout bounds both
        // its connect and its reads, because a cancel can get stuck on a server the session itself
        // is not stuck on.
        val cancelStream = try {
            PgStream(stream.host, stream.port, stream.cancelSignalTimeoutSecs)
        } catch (e: Exception) {
            // Never opened, so there is nothing to clean up and nothing to report.
            logger.debug(e) { "$pid Could not open a connection to carry the cancel request" }
            return
        }

        try {
            // The key gets the same TLS treatment the session asked for: under REQUIRE and stronger
            // a server that will not encrypt makes negotiate() throw, so the request is never sent
            // rather than being sent in the clear.
            stream.sslConfiguration?.let {
                SslNegotiator.negotiate(cancelStream, stream.host, stream.port, it)
            }
            cancelStream.sendMessage(CancelRequestMessage(stream.processId, stream.secretKey))
            cancelStream.flush()
            cancelStream.awaitServerClose()
            logger.debug { "$pid Cancel request sent" }
        } catch (e: Exception) {
            // Ignore errors during cancellation. Only the TLS negotiation, the send and the flush
            // reach here - the wait reports its outcome rather than throwing - so arriving here
            // means the request never made it onto the wire.
            logger.debug(e) { "$pid Cancel request could not be sent" }
        } finally {
            cancelStream.dropSocket()
        }
    }

    //------------------------------------------SEARCH PATH-------------------------------------------------------------

    /**
     * Parses a PostgreSQL search_path parameter string which may contain quoted identifiers
     * and commas within quotes (e.g., '"$user", public', '"schema,with,comma"').
     */
    private fun parseSearchPath(param: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < param.length) {
            val c = param[i]
            if (c == '"') {
                if (inQuotes && i + 1 < param.length && param[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trimEnd())
                current.clear()
            } else if (c.isWhitespace() && !inQuotes && current.isEmpty()) {
                // skip leading whitespace
            } else {
                current.append(c)
            }
            i++
        }
        result.add(current.toString().trimEnd())
        return result.filter { it.isNotEmpty() }
    }

    /**
     * Retrieves the current search path. Since we enforce PostgreSQL 18+, this value
     * is always kept up-to-date automatically via ParameterStatus messages from the server.
     *
     * @return A list of schema names representing the current search path.
     */
    fun getSearchPath(): List<String> {
        checkClosed()
        val paramSearchPath = stream.parameters["search_path"]
        if (paramSearchPath != null) {
            if (paramSearchPath == lastSearchPathString && cachedSearchPath != null) {
                return cachedSearchPath!!
            }
            val parsed = parseSearchPath(paramSearchPath)
            lastSearchPathString = paramSearchPath
            cachedSearchPath = parsed
            return parsed
        }
        // Fallback in rare cases (e.g., mocked test server)
        return listOf("public")
    }

    //--------------------------------------------READ ONLY-------------------------------------------------------------
    private var readOnlyFlag: Boolean = false

    override fun setReadOnly(readOnly: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        if (this.readOnlyFlag != readOnly) {
            val modeStr = if (readOnly) "READ ONLY" else "READ WRITE"
            val query = buildString {
                append("SET SESSION CHARACTERISTICS AS TRANSACTION $modeStr")
                if (transactionState == TransactionState.IN_TRANSACTION) {
                    append("; SET TRANSACTION $modeStr")
                }
            }
            queryExecutor.execute(query)
            this.readOnlyFlag = readOnly
            logger.debug { "$pid Session characteristics set to $modeStr" }
        }
    }

    override fun isReadOnly(): Boolean = wrapSqlException { // required by Hikari
        checkClosed()
        return@wrapSqlException readOnlyFlag
    }

    //-----------------------------------------TRANSACTIONS-------------------------------------------------------------

    private var autoCommitFlag: Boolean = true

    private var transactionIsolationLevel: Int = Connection.TRANSACTION_READ_COMMITTED

    val transactionState: TransactionState
        get() = TransactionState.fromChar(stream.transactionStatus)


    override fun setAutoCommit(autoCommit: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        if (this.autoCommitFlag != autoCommit) {
            if (autoCommit) refuseCommitOfFailedTransaction()
            this.autoCommitFlag = autoCommit
            if (autoCommit) {
                queryExecutor.execute("COMMIT")
                logger.debug { "$pid Auto-commit enabled; open transaction committed" }
            } else {
                queryExecutor.execute("BEGIN")
                logger.debug { "$pid Auto-commit disabled; transaction started" }
            }
        }
    }

    override fun getAutoCommit(): Boolean = wrapSqlException { // required by Hikari
        checkClosed()
        return@wrapSqlException autoCommitFlag
    }

    override fun commit() = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        refuseCommitOfFailedTransaction()
        queryExecutor.execute("COMMIT; BEGIN")
        logger.debug { "$pid Transaction committed; new transaction started" }
    }

    /** Raises `COMMIT_OF_FAILED_TRANSACTION` where an earlier error aborted the transaction. */
    private fun refuseCommitOfFailedTransaction() {
        if (transactionState == TransactionState.FAILED) {
            throw InvalidOperationException(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION)
        }
    }

    override fun rollback() = wrapSqlException { // required by Hikari
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        queryExecutor.execute("ROLLBACK; BEGIN")
        logger.debug { "$pid Transaction rolled back; new transaction started" }
    }

    override fun setTransactionIsolation(level: Int) = wrapSqlException { // required by Hikari
        checkClosed()
        val levelStr = when (level) {
            Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED"
            Connection.TRANSACTION_READ_COMMITTED -> "READ COMMITTED"
            Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ"
            Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE"
            else -> throw InvalidOperationException(
                InvalidOperationExceptionReason.INVALID_ARGUMENT,
                details = "Unsupported transaction isolation level: $level"
            )
        }
        val query = buildString {
            append("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL $levelStr")
            if (transactionState == TransactionState.IN_TRANSACTION) {
                append("; SET TRANSACTION ISOLATION LEVEL $levelStr")
            }
        }
        queryExecutor.execute(query)
        this.transactionIsolationLevel = level
        logger.debug { "$pid Isolation level set to $levelStr" }
    }

    override fun getTransactionIsolation(): Int = wrapSqlException { // required by Hikari
        checkClosed()
        return@wrapSqlException transactionIsolationLevel
    }

    //-------------------------------------------------SAVEPOINTS-------------------------------------------------------
    private var savepointIdCounter: Int = 1

    override fun setSavepoint(): Savepoint = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot set a savepoint when auto-commit is enabled")
        val sp = OctaviusSavepointImpl(savepointIdCounter++)
        queryExecutor.execute("SAVEPOINT ${sp.pgName}")
        logger.debug { "$pid Savepoint ${sp.pgName} set" }
        return@wrapSqlException sp
    }

    override fun setSavepoint(name: String?): Savepoint = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot set a savepoint when auto-commit is enabled")
        if (name == null) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Savepoint name cannot be null")
        val sp = OctaviusSavepointImpl(name)
        queryExecutor.execute("SAVEPOINT ${sp.pgName}")
        logger.debug { "$pid Savepoint ${sp.pgName} set" }
        return@wrapSqlException sp
    }

    override fun rollback(savepoint: Savepoint?) = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot rollback to a savepoint when auto-commit is enabled")
        if (savepoint !is OctaviusSavepointImpl) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Unsupported savepoint")
        queryExecutor.execute("ROLLBACK TO SAVEPOINT ${savepoint.pgName}")
        logger.debug { "$pid Rolled back to savepoint ${savepoint.pgName}" }
    }

    override fun releaseSavepoint(savepoint: Savepoint?) = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot release a savepoint when auto-commit is enabled")
        if (savepoint !is OctaviusSavepointImpl) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Unsupported savepoint")
        queryExecutor.execute("RELEASE SAVEPOINT ${savepoint.pgName}")
        logger.debug { "$pid Savepoint ${savepoint.pgName} released" }
    }

    //--------------------------STATEMENT (SUPPORTED ONLY UPDATE AND EXECUTE)-------------------------------------------
    // Support for basic Statement is needed for connection pools (e.g., HikariCP connectionInitSql)
    override fun createStatement(): Statement = wrapSqlException {
        checkClosed()
        return@wrapSqlException OctaviusStatement(this)
    }

    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int): Statement = wrapSqlException {
        checkClosed()
        return@wrapSqlException OctaviusStatement(this)
    }

    override fun createStatement(resultSetType: Int, resultSetConcurrency: Int, resultSetHoldability: Int): Statement = wrapSqlException {
        checkClosed()
        return@wrapSqlException OctaviusStatement(this)
    }

    //-------------------------------------NOT IMPLEMENTED--------------------------------------------------------------
    private fun unsupported(): Nothing =
        throw InvalidOperationException(InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED)

    // Neither is connection state in PostgreSQL. A catalog is the database itself, which nothing on
    // an open connection can change, and a schema is not a setting but the head of `search_path` -
    // reported by the server and read back through getSearchPath(). The setters used to accept
    // whatever they were given and do nothing with it, so a pool configured with a schema set none
    // and every query after it ran somewhere else, while the getters answered with constants that
    // were true of nothing. A pool asks only where it was configured to and never calls the getters
    // itself, so what raises here is exactly the setting that was being ignored.
    override fun setSchema(schema: String?) = unsupported()
    override fun getSchema(): String = unsupported()
    override fun setCatalog(catalog: String?) = unsupported()
    override fun getCatalog(): String = unsupported()

    // Replaced by typeManager
    override fun createArrayOf(typeName: String?, elements: Array<out Any>?): java.sql.Array = unsupported()
    override fun createStruct(typeName: String?, attributes: Array<out Any>?): Struct = unsupported()

    override fun createSQLXML(): SQLXML = unsupported()

    // Postgres does not have these types
    override fun createClob(): Clob = unsupported()
    override fun createBlob(): Blob = unsupported()
    override fun createNClob(): NClob = unsupported()

    override fun prepareStatement(sql: String?): PreparedStatement = unsupported()
    override fun prepareStatement(sql: String?, resultSetType: Int, resultSetConcurrency: Int): PreparedStatement =
        unsupported()

    override fun prepareStatement(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int
    ): PreparedStatement = unsupported()

    override fun prepareStatement(sql: String?, autoGeneratedKeys: Int): PreparedStatement = unsupported()
    override fun prepareStatement(sql: String?, columnIndexes: IntArray?): PreparedStatement = unsupported()
    override fun prepareStatement(sql: String?, columnNames: Array<out String>?): PreparedStatement = unsupported()
    override fun prepareCall(sql: String?): CallableStatement = unsupported()
    override fun prepareCall(sql: String?, resultSetType: Int, resultSetConcurrency: Int): CallableStatement =
        unsupported()

    override fun prepareCall(
        sql: String?,
        resultSetType: Int,
        resultSetConcurrency: Int,
        resultSetHoldability: Int
    ): CallableStatement = unsupported()

    // No support for ResultSet
    override fun setHoldability(holdability: Int) = unsupported()
    override fun getHoldability(): Int = unsupported()

    // Based on the type catalog
    override fun getTypeMap(): MutableMap<String, Class<*>> = unsupported()
    override fun setTypeMap(map: MutableMap<String, Class<*>>?) = unsupported()
}
