package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.concurrent.OctaviusDispatchers
import io.github.octaviusframework.driver.copy.CopyManager
import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.findOctaviusCause
import io.github.octaviusframework.driver.jdbc.OctaviusConnection
import io.github.octaviusframework.driver.jdbc.unwrap
import io.github.octaviusframework.driver.lo.LargeObjectManager
import io.github.octaviusframework.driver.notification.NotificationManager
import io.github.octaviusframework.driver.query.NamedParameterQuery
import io.github.octaviusframework.driver.query.NativeQuery
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.transaction.OctaviusSavepoint
import io.github.octaviusframework.driver.transaction.TransactionManager
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.SQLException

private val logger = KotlinLogging.logger {}


/**
 * The [OctaviusSession] over a JDBC [Connection], possibly a pool's proxy, and the [OctaviusConnection] behind it.
 *
 * @property ownsConnection Whether [close] gives the connection back as well as resetting it.
 */
internal class OctaviusSessionImpl(
    private val rawConnection: Connection,
    private val ownsConnection: Boolean = true
) : OctaviusSession {

    internal val octaviusConnection: OctaviusConnection = rawConnection.unwrap()

    /**
     * Whether a connection the session does not own was already in a transaction, begun or waiting, when the
     * session opened. That transaction is the owner's, and [close] leaves it open.
     */
    private val inheritedTransaction: Boolean =
        !ownsConnection && octaviusConnection.transactionState != TransactionState.IDLE

    /**
     * Whether [close] or [abort] has run. [octaviusConnection] may already be the next borrower's, and only this
     * keeps a stale session off it.
     */
    @Volatile
    private var sessionClosed: Boolean = false

    override val typeManager: TypeManager = TypeManager(octaviusConnection.catalogHolder) { octaviusConnection.getTypeLookupOrder() }
        get() { checkOpen(); return field }

    override val notifications: NotificationManager = NotificationManager(this)
        get() { checkOpen(); return field }

    override val copy: CopyManager = CopyManager(octaviusConnection.stream, octaviusConnection.queryExecutor)
        get() { checkOpen(); return field }

    override val largeObjects: LargeObjectManager = LargeObjectManager(this)
        get() { checkOpen(); return field }

    override val transaction: TransactionManager = TransactionManager(this, octaviusConnection)
        get() { checkOpen(); return field }

    override val transactionState: TransactionState
        get() {
            checkOpen()
            return octaviusConnection.transactionState
        }

    /** Refuses anything asked of a session already given up, as a closed connection. */
    private fun checkOpen() {
        if (sessionClosed) throw NetworkException(
            NetworkExceptionReason.CONNECTION_CLOSED,
            details = "This session has been closed; its connection has gone back to the pool or been discarded",
            sqlState = "08003"
        )
    }

    /** The backend process id every log line carries. */
    private val pid: String get() = "[PID: ${octaviusConnection.stream.processId}]"

    override fun reloadTypes() {
        checkOpen()
        GlobalCatalogStore.reload(
            octaviusConnection.databaseKey,
            octaviusConnection.catalogHolder,
            octaviusConnection.queryExecutor
        )
    }

    override fun createNativeQuery(sql: String): NativeQuery {
        checkOpen()
        octaviusConnection.checkClosed()
        return NativeQuery(sql, octaviusConnection.queryExecutor, typeManager)
    }

    override fun createNamedQuery(sql: String): NamedParameterQuery {
        checkOpen()
        octaviusConnection.checkClosed()
        return NamedParameterQuery(sql, octaviusConnection.queryExecutor, typeManager)
    }

    override fun cancelQuery() {
        checkOpen()
        octaviusConnection.cancelQuery()
    }

    override val searchPath: List<String>
        get() {
            checkOpen()
            return octaviusConnection.getSearchPath()
        }

    // ------------------------------------------Pool Connection--------------------------------------------------------

    /**
     * Runs [block] on the JDBC connection and raises a `SQLException` as the driver's exception in its chain, or as
     * a [NetworkException] where it has none, such as a pool proxy's over a connection it took back.
     */
    private inline fun <T> unwrapSqlException(block: () -> T): T {
        checkOpen()
        try {
            return block()
        } catch (e: SQLExceptionWrapper) {
            throw e.wrappedException
        } catch (e: SQLException) {
            throw e.findOctaviusCause() ?: NetworkException(
                NetworkExceptionReason.CONNECTION_ERROR,
                details = e.message,
                cause = e,
                sqlState = e.sqlState ?: "08006"
            )
        }
    }

    override var autoCommit: Boolean
        get() = unwrapSqlException { rawConnection.autoCommit }
        set(value) {
            unwrapSqlException { rawConnection.autoCommit = value }
        }

    override var readOnly: Boolean
        get() = unwrapSqlException { rawConnection.isReadOnly }
        set(value) {
            unwrapSqlException { rawConnection.isReadOnly = value }
        }

    override var transactionIsolationLevel: TransactionIsolationLevel
        get() = unwrapSqlException { TransactionIsolationLevel.fromJdbcValue(rawConnection.transactionIsolation) }
        set(value) {
            unwrapSqlException { rawConnection.transactionIsolation = value.jdbcValue }
        }

    override var networkTimeout: Int
        get() = unwrapSqlException { rawConnection.networkTimeout }
        set(value) {
            unwrapSqlException { rawConnection.setNetworkTimeout(OctaviusDispatchers.VirtualExecutor, value) }
        }

    // Answers rather than raises on a session already given up, as JDBC's `isValid` does.
    override fun isValid(timeout: Int): Boolean = !sessionClosed && try {
        rawConnection.isValid(timeout)
    } catch (e: SQLExceptionWrapper) {
        throw e.wrappedException
    }

    override fun commit() = unwrapSqlException { rawConnection.commit() }

    override fun rollback() = unwrapSqlException { rawConnection.rollback() }

    override fun setSavepoint(): OctaviusSavepoint {
        return unwrapSqlException { rawConnection.setSavepoint() as OctaviusSavepoint }
    }

    override fun setSavepoint(name: String): OctaviusSavepoint {
        return unwrapSqlException { rawConnection.setSavepoint(name) as OctaviusSavepoint }
    }

    override fun rollback(savepoint: OctaviusSavepoint) {
        unwrapSqlException { rawConnection.rollback(savepoint as java.sql.Savepoint) }
    }

    override fun releaseSavepoint(savepoint: OctaviusSavepoint) {
        unwrapSqlException { rawConnection.releaseSavepoint(savepoint as java.sql.Savepoint) }
    }

    // -------------------------------------------Close/Abort-----------------------------------------------------------

    /** Undoes what the session left on its connection for the next borrower: a transaction and its `LISTEN`s. */
    private fun resetConnectionState() {
        // The rollback first: inside an open transaction an UNLISTEN waits for the commit, inside a failed one it
        // is refused. In the owner's transaction it stands or falls with it.
        if (!inheritedTransaction) rollbackUnfinishedTransaction()
        notifications.releaseSubscriptions()
    }

    /**
     * Rolls back a transaction the session began and left open on the server, which no pool would: HikariCP rolls
     * back only work that went through its own proxies.
     */
    private fun rollbackUnfinishedTransaction() {
        if (octaviusConnection.serverTransactionState == TransactionState.IDLE) return
        octaviusConnection.queryExecutor.control("ROLLBACK")
    }

    override fun abort() {
        // Before the abort, which may raise.
        sessionClosed = true
        try {
            rawConnection.abort(OctaviusDispatchers.VirtualExecutor)
        } catch (_: SQLExceptionWrapper) {
            // Expected: the abort throws so a pool evicts the connection, and the connection logs it.
        } catch (e: Exception) {
            // Not the abort's own signal, so the connection may be left as it stood.
            logger.debug(e) { "$pid Aborting the session raised" }
        }
    }

    override fun close() {
        if (sessionClosed) return
        try {
            if (octaviusConnection.isClosed || octaviusConnection.stream.isBroken) {
                // Broken underneath, so the pool has to evict it.
                abort()
            } else if (octaviusConnection.stream.copyInProgress) {
                // Evicted rather than cancelled: cancelling a COPY OUT reads the rest of the export on this thread.
                logger.warn { "$pid Session closed with a COPY still in progress; the connection is aborted rather than reset" }
                abort()
            } else {
                try {
                    resetConnectionState()
                } catch (e: Exception) {
                    // Evicted rather than given back with the leftovers; this line says why.
                    logger.warn(e) { "$pid Could not reset connection state on close; aborting the connection instead" }
                    abort()
                    return
                }
                // A connection someone else owns is theirs to give back.
                if (ownsConnection) rawConnection.close()
            }
        } catch (e: Exception) {
            logger.debug(e) { "$pid Closing the session raised" }
        } finally {
            // Last: the reset above runs statements through this session's connection.
            sessionClosed = true
        }
    }
}
