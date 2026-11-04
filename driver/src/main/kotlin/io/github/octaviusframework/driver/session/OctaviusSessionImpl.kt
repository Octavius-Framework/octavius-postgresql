package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.concurrent.OctaviusDispatchers
import io.github.octaviusframework.driver.copy.CopyManager
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
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
     * Whether [close] or [abort] has run. [octaviusConnection] may already be the next borrower's, and this keeps a
     * stale session off it.
     */
    @Volatile
    private var sessionClosed: Boolean = false

    /** Whether [abort] was called. The connection is gone then, where after [close] it may well be fine. */
    @Volatile
    private var aborted: Boolean = false

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

    /**
     * Refuses anything asked of a session already given up. After [abort] that is a connection that is gone, and
     * otherwise a closed handle, over a connection that may be fine and already the next borrower's.
     */
    private fun checkOpen() {
        if (aborted) throw NetworkException(
            NetworkExceptionReason.CONNECTION_CLOSED,
            details = "This session was aborted and its connection discarded",
            sqlState = "08003"
        )
        if (sessionClosed) throw InvalidOperationException(
            InvalidOperationExceptionReason.RESOURCE_CLOSED,
            "This session has been closed; its connection has gone back to the pool or been discarded"
        )
        if (connectionTakenBack()) throw InvalidOperationException(
            InvalidOperationExceptionReason.RESOURCE_CLOSED,
            "The connection under this session went back before the session was closed, and may be the next borrower's by now"
        )
    }

    /**
     * Whether the [rawConnection] a pool handed out was closed while the [octaviusConnection] behind it stayed open:
     * given back, with this session still holding on to it.
     */
    private fun connectionTakenBack(): Boolean {
        // Asked only of one still alive: isClosed latches a broken connection, and what is asked next would no
        // longer say what broke it.
        if (octaviusConnection.isClosedFlag || octaviusConnection.stream.isBroken) return false
        return try {
            rawConnection.isClosed
        } catch (_: SQLException) {
            // One that will not say whether it is closed is not one to run on.
            true
        }
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
     * a [NetworkException] where it has none, such as a pool proxy's over a connection it evicted.
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
        aborted = true
        discardConnection()
    }

    /** Gives up the session and aborts its connection, so a pool evicts it. */
    private fun discardConnection() {
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
            if (connectionTakenBack()) {
                // Not this session's to touch any more: what is on the connection may be the next borrower's.
                logger.warn { "$pid Session closed after its connection had gone back; what it left on the connection stays there" }
            } else if (octaviusConnection.isClosed || octaviusConnection.stream.isBroken) {
                // Broken underneath, so the pool has to evict it.
                discardConnection()
            } else if (octaviusConnection.stream.copyInProgress) {
                // Evicted rather than cancelled: cancelling a COPY OUT reads the rest of the export on this thread.
                logger.warn { "$pid Session closed with a COPY still in progress; the connection is aborted rather than reset" }
                discardConnection()
            } else {
                try {
                    resetConnectionState()
                } catch (e: Exception) {
                    // Evicted rather than given back with the leftovers; this line says why.
                    logger.warn(e) { "$pid Could not reset connection state on close; aborting the connection instead" }
                    discardConnection()
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
