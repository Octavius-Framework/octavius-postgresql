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

/** The JDBC [Connection], implemented only in part: queries run through an `OctaviusSession` on it. */
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

    @Volatile
    var isClosedFlag: Boolean = false

    /**
     * Whether [close] or [abort] has run. Unlike [isClosedFlag], a broken stream does not set it, so the first
     * [abort] on one still raises and the pool lets it go.
     */
    @Volatile
    private var closeRequested: Boolean = false

    private var cachedSearchPath: SearchPath? = null


    /** The backend process id every log line carries, as `pg_stat_activity` and the server's log show it. */
    private val pid: String get() = "[PID: ${stream.processId}]"

    /**
     * Raises unless this connection is usable. A broken stream latches [isClosedFlag].
     *
     * @throws NetworkException `CONNECTION_CLOSED` (`08003`) if it was closed, `CONNECTION_ERROR` (`08006`)
     *   if the stream broke, with the server's reason in `details` where the server ended the session.
     */
    fun checkClosed() {
        if (isClosedFlag) throw NetworkException(NetworkExceptionReason.CONNECTION_CLOSED, sqlState = "08003")
        if (stream.isBroken) {
            isClosedFlag = true
            val terminatedBy = stream.terminatedBy
            val details = if (terminatedBy != null) {
                "The server ended this session (${terminatedBy.code}): ${terminatedBy.message}"
            } else {
                "Underlying stream is broken"
            }
            throw NetworkException(NetworkExceptionReason.CONNECTION_ERROR, details = details, sqlState = "08006")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(iface: Class<T>): T = wrapSqlException {
        if (iface.isInstance(this)) return@wrapSqlException this as T
        throw InvalidOperationException(InvalidOperationExceptionReason.UNWRAP_ERROR, details = "Cannot unwrap to ${iface.name}")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    override fun nativeSQL(sql: String?): String = sql?.let { SqlParameterParser.parse(sql).transformedSql } ?: ""

    override fun close() {
        if (!closeRequested) {
            closeRequested = true
            isClosedFlag = true
            // Also on a stream that broke: a socket a read timed out on is still open until this closes it.
            stream.close()
        }
    }

    override fun isClosed(): Boolean {
        if (stream.isBroken) isClosedFlag = true
        return isClosedFlag
    }

    override fun getMetaData(): DatabaseMetaData = OctaviusDatabaseMetaData // required by Spring

    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {} // required by Hikari


    override fun isValid(timeout: Int): Boolean = wrapSqlException { // required by Hikari
        if (timeout < 0) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Timeout for isValid() cannot be negative, was $timeout"
        )
        if (isClosed()) return@wrapSqlException false

        // Under the exchange lock, so the swapped timeout never shortens a query running on another thread.
        stream.lock.withLock {
            val originalTimeout = try {
                stream.networkTimeout
            } catch (e: NetworkException) {
                logger.debug(e) { "$pid Could not read the network timeout; reporting the connection as invalid" }
                return@withLock false
            }
            // Seconds, 0 for none; widened first so a large value saturates instead of overflowing.
            val requested = (timeout.toLong() * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Only ever tightened: isValid(0) must not leave the probe hanging on a dead socket.
            val tightens = requested != 0 && (originalTimeout == 0 || requested < originalTimeout)

            try {
                if (tightens) stream.networkTimeout = requested
                // A probe, so it opens nothing: a health check must not begin the transaction a BEGIN is waiting for.
                queryExecutor.control("")
                true
            } catch (e: InvalidOperationException) {
                // Called from inside this thread's own exchange: a caller bug, not a dead connection.
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


    override fun abort(executor: Executor?) = wrapSqlException {
        if (executor == null) throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Executor cannot be null"
        )

        // Not isClosedFlag, which a broken stream latches too; see closeRequested.
        if (closeRequested) return@wrapSqlException
        closeRequested = true
        isClosedFlag = true

        logger.debug { "$pid Connection aborted" }

        executor.execute {
            stream.close()
        }
        // Signal for Hikari to evict Connection
        throw NetworkException(
            NetworkExceptionReason.CONNECTION_ABORTED,
            details = "Connection explicitly aborted by Octavius",
            sqlState = "08000"
        )
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
     * Asks the server, over a second connection, to cancel what this one is running, without waiting for the
     * outcome. A cancelled statement raises
     * [ExecutionAbortedException][io.github.octaviusframework.driver.exception.ExecutionAbortedException] where
     * it runs; a request that could not be sent is not reported.
     *
     * @throws NetworkException if this connection is already closed or broken.
     */
    fun cancelQuery() {
        checkClosed()

        // To the address this connection reached, not by name: the key means something only to this server.
        val cancelStream = try {
            PgStream(stream.host, stream.port, stream.address, stream.cancelSignalTimeoutSecs)
        } catch (e: Exception) {
            // Never opened, so there is nothing to clean up and nothing to report.
            logger.debug(e) { "$pid Could not open a connection to carry the cancel request" }
            return
        }

        try {
            // Under the session's TLS settings, so the key is never sent in plaintext where they forbid it.
            stream.sslConfiguration?.let {
                SslNegotiator.negotiate(cancelStream, stream.host, stream.port, it)
            }
            cancelStream.writeMessage(CancelRequestMessage(stream.processId, stream.secretKey))
            cancelStream.flush()
            cancelStream.awaitServerClose()
            logger.debug { "$pid Cancel request sent" }
        } catch (e: Exception) {
            // Only negotiating, sending and flushing throw, so the request never went out.
            logger.debug(e) { "$pid Cancel request could not be sent" }
        } finally {
            cancelStream.dropSocket()
        }
    }

    //------------------------------------------SEARCH PATH-------------------------------------------------------------

    /**
     * The search path the server last reported, with the `session_authorization` its `$user` stands for. Parsed
     * again only when either report moves.
     */
    private fun currentSearchPath(): SearchPath {
        val setting = stream.parameters.getValue("search_path")
        val sessionUser = stream.parameters["session_authorization"]
        cachedSearchPath?.let { if (it.isFor(setting, sessionUser)) return it }
        return SearchPath(setting, sessionUser).also { cachedSearchPath = it }
    }

    /** The schemas the current search path lists, `$user` and `pg_temp` as written. */
    fun getSearchPath(): List<String> {
        checkClosed()
        return currentSearchPath().entries
    }

    /** The schemas an unqualified type name is looked up in, in the server's order. */
    fun getTypeLookupOrder(): List<String> {
        checkClosed()
        return currentSearchPath().typeLookupOrder
    }

    //--------------------------------------------READ ONLY-------------------------------------------------------------
    private var readOnlyFlag: Boolean = false

    override fun setReadOnly(readOnly: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        if (this.readOnlyFlag != readOnly) {
            val modeStr = if (readOnly) "READ ONLY" else "READ WRITE"
            val query = buildString {
                append("SET SESSION CHARACTERISTICS AS TRANSACTION $modeStr")
                // Only a transaction the server has begun. One whose BEGIN is still waiting picks the session
                // characteristic up when that BEGIN goes out.
                if (serverTransactionState == TransactionState.IN_TRANSACTION) {
                    append("; SET TRANSACTION $modeStr")
                }
            }
            queryExecutor.control(query)
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

    /** The server's transaction state, with a `BEGIN` still waiting counted as `IN_TRANSACTION`. */
    val transactionState: TransactionState
        get() {
            val reported = serverTransactionState
            return if (reported == TransactionState.IDLE && !autoCommitFlag) TransactionState.IN_TRANSACTION else reported
        }

    /** The transaction state as the server reported it on its last `ReadyForQuery`, a waiting `BEGIN` not counted. */
    internal val serverTransactionState: TransactionState
        get() = TransactionState.fromChar(stream.transactionStatus)

    /**
     * Switching off sends nothing, leaving `BEGIN` to the first statement; switching on commits a transaction a
     * statement began.
     */
    override fun setAutoCommit(autoCommit: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        if (this.autoCommitFlag != autoCommit) {
            if (autoCommit) refuseCommitOfFailedTransaction()
            this.autoCommitFlag = autoCommit
            queryExecutor.beginsTransactions = !autoCommit
            queryExecutor.deferredTerms = null
            if (!autoCommit) {
                logger.debug { "$pid Auto-commit disabled; the first statement begins the transaction" }
            } else if (serverTransactionState == TransactionState.IDLE) {
                logger.debug { "$pid Auto-commit enabled; no statement had begun a transaction, so none to commit" }
            } else {
                queryExecutor.control("COMMIT")
                logger.debug { "$pid Auto-commit enabled; open transaction committed" }
            }
        }
    }

    /**
     * Hands [terms] - `SET TRANSACTION`, `SET LOCAL` - to the waiting `BEGIN`, or sends them now where a
     * hand-written `BEGIN` already opened the transaction.
     */
    internal fun deferTransactionTerms(terms: String) {
        checkClosed()
        if (!autoCommitFlag && serverTransactionState == TransactionState.IDLE) {
            queryExecutor.deferredTerms = terms
        } else {
            queryExecutor.execute(terms)
        }
    }

    override fun getAutoCommit(): Boolean = wrapSqlException { // required by Hikari
        checkClosed()
        return@wrapSqlException autoCommitFlag
    }

    /**
     * Commits and leaves the next `BEGIN` to the next statement, so its `now()` is not fixed here. A transaction no
     * statement began ends with nothing sent.
     */
    override fun commit() = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        refuseCommitOfFailedTransaction()
        endTransaction("COMMIT")
    }

    /** Raises `COMMIT_OF_FAILED_TRANSACTION` where an earlier error aborted the transaction. */
    private fun refuseCommitOfFailedTransaction() {
        if (transactionState == TransactionState.FAILED) {
            throw InvalidOperationException(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION)
        }
    }

    /** Rolls back, on the same terms as [commit]. */
    override fun rollback() = wrapSqlException { // required by Hikari
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        endTransaction("ROLLBACK")
    }

    /** Sends [command] where a statement began a transaction, and drops the terms a waiting `BEGIN` held. */
    private fun endTransaction(command: String) {
        queryExecutor.deferredTerms = null
        if (serverTransactionState == TransactionState.IDLE) {
            logger.debug { "$pid $command with no statement since the last; nothing had begun, so nothing sent" }
            return
        }
        queryExecutor.control(command)
        logger.debug { "$pid $command sent; the next statement begins the next transaction" }
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
            // As in setReadOnly: a waiting BEGIN picks the session characteristic up by itself.
            if (serverTransactionState == TransactionState.IN_TRANSACTION) {
                append("; SET TRANSACTION ISOLATION LEVEL $levelStr")
            }
        }
        queryExecutor.control(query)
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
        queryExecutor.control("ROLLBACK TO SAVEPOINT ${savepoint.pgName}")
        logger.debug { "$pid Rolled back to savepoint ${savepoint.pgName}" }
    }

    override fun releaseSavepoint(savepoint: Savepoint?) = wrapSqlException {
        checkClosed()
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot release a savepoint when auto-commit is enabled")
        if (savepoint !is OctaviusSavepointImpl) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Unsupported savepoint")
        queryExecutor.control("RELEASE SAVEPOINT ${savepoint.pgName}")
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

    // Neither is connection state in PostgreSQL: a catalog is the database, and a schema the head of
    // `search_path`. Raising makes a pool configured with one fail rather than run elsewhere.
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
