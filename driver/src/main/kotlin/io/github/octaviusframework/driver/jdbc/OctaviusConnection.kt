package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.*
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.CancelRequestMessage
import io.github.octaviusframework.driver.parser.SqlParameterParser
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.session.SearchPath
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.octaviusframework.driver.ssl.SslNegotiator
import io.github.octaviusframework.driver.transaction.TransactionControl
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

    /** The backend process id every log line carries, as `pg_stat_activity` and the server's log show it. */
    private val pid: String get() = "[PID: ${stream.processId}]"

    //---------------------------------------------------ABORT/CLOSE----------------------------------------------------
    @Volatile
    var isClosedFlag: Boolean = false

    /**
     * Whether [close] or [abort] has run. Unlike [isClosedFlag], a broken stream does not set it, so the first
     * [abort] on one still raises and the pool lets it go.
     */
    @Volatile
    private var closeRequested: Boolean = false

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

    //----------------------------------------------------VALIDATION----------------------------------------------------
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

    //-------------------------------------------------NETWORK TIMEOUT--------------------------------------------------
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

    //------------------------------------------------------CANCEL------------------------------------------------------
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

    //---------------------------------------------------SEARCH PATH----------------------------------------------------
    private var cachedSearchPath: SearchPath? = null

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

    //---------------------------------------------------TRANSACTIONS---------------------------------------------------
    /** Auto-commit, read only, isolation and savepoints, each changed through the methods below. */
    internal val transactionControl = TransactionControl(stream, queryExecutor)

    override fun setReadOnly(readOnly: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.setReadOnly(readOnly)
    }

    override fun isReadOnly(): Boolean = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.readOnly
    }

    override fun setAutoCommit(autoCommit: Boolean) = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.setAutoCommit(autoCommit)
    }

    override fun getAutoCommit(): Boolean = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.autoCommit
    }

    override fun commit() = wrapSqlException {
        checkClosed()
        transactionControl.commit()
    }

    override fun rollback() = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.rollback()
    }

    override fun setTransactionIsolation(level: Int) = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.setIsolation(TransactionIsolationLevel.fromJdbcValue(level))
    }

    override fun getTransactionIsolation(): Int = wrapSqlException { // required by Hikari
        checkClosed()
        transactionControl.isolation.jdbcValue
    }

    override fun setSavepoint(): Savepoint = wrapSqlException {
        checkClosed()
        transactionControl.setSavepoint()
    }

    override fun setSavepoint(name: String?): Savepoint = wrapSqlException {
        checkClosed()
        transactionControl.setSavepoint(name)
    }

    override fun rollback(savepoint: Savepoint?) = wrapSqlException {
        checkClosed()
        transactionControl.rollback(savepoint)
    }

    override fun releaseSavepoint(savepoint: Savepoint?) = wrapSqlException {
        checkClosed()
        transactionControl.releaseSavepoint(savepoint)
    }

    //----------------------------------STATEMENT (SUPPORTED ONLY UPDATE AND EXECUTE)-----------------------------------
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

    override fun nativeSQL(sql: String?): String = sql?.let { SqlParameterParser.parse(sql).transformedSql } ?: ""

    //-----------------------------------------------------WRAPPER------------------------------------------------------
    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(iface: Class<T>): T = wrapSqlException {
        if (iface.isInstance(this)) return@wrapSqlException this as T
        throw InvalidOperationException(InvalidOperationExceptionReason.UNWRAP_ERROR, details = "Cannot unwrap to ${iface.name}")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)

    //----------------------------------------------METADATA AND WARNINGS-----------------------------------------------
    override fun getMetaData(): DatabaseMetaData = OctaviusDatabaseMetaData // required by Spring

    override fun getWarnings(): SQLWarning? = null
    override fun clearWarnings() {} // required by Hikari

    //---------------------------------------------------CLIENT INFO----------------------------------------------------
    override fun setClientInfo(name: String?, value: String?) = unsupported()
    override fun setClientInfo(properties: Properties?) = unsupported()
    override fun getClientInfo(name: String?): String = unsupported()
    override fun getClientInfo(): Properties = Properties()

    //-------------------------------------------------NOT IMPLEMENTED--------------------------------------------------
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
