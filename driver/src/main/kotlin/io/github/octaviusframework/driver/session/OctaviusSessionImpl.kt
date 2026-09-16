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
 * Internal implementation of the [OctaviusSession] interface.
 *
 * This class wraps a raw JDBC [Connection] (which could be pooled) and delegates
 * operations to the underlying [OctaviusConnection].
 *
 * @property ownsConnection Whether [close] should close the connection as well as reset it. False
 * where the connection was handed to the session by something that gives it back itself.
 */
internal class OctaviusSessionImpl(
    private val rawConnection: Connection,
    private val ownsConnection: Boolean = true
) : OctaviusSession {

    internal val octaviusConnection: OctaviusConnection = rawConnection.unwrap()

    /**
     * Whether this session has been given up, by [close] or by [abort].
     *
     * A pooled connection outlives the session borrowed through it: closing the session hands the
     * connection back, and the next borrower may already be running queries on it. The proxy that
     * came with it goes dead at that moment, but [octaviusConnection] does not - it is the live
     * connection, now somebody else's - so nothing below this class would stop a stale session from
     * reaching it. This is what does.
     */
    @Volatile
    private var sessionClosed: Boolean = false

    // Each of these is built once and handed out through a getter that first checks the session is
    // still the connection's. Two of them reach past every other guard in this class - the copy
    // manager holds the stream itself, and a listener loop reads messages off it directly - so the
    // check belongs at the point they are handed out. The rest are gated for one rule rather than a
    // rule with exceptions.
    override val typeManager: TypeManager = TypeManager(octaviusConnection.catalogHolder) { octaviusConnection.getSearchPath() }
        get() { checkOpen(); return field }

    override val notifications: NotificationManager = NotificationManager(this)
        get() { checkOpen(); return field }

    override val copy: CopyManager = CopyManager(octaviusConnection.stream)
        get() { checkOpen(); return field }

    override val largeObjects: LargeObjectManager = LargeObjectManager(this)
        get() { checkOpen(); return field }

    override val transaction: TransactionManager = TransactionManager(this)
        get() { checkOpen(); return field }

    override val transactionState: TransactionState
        get() {
            checkOpen()
            return octaviusConnection.transactionState
        }

    /**
     * Refuses anything asked of a session that has already been given up.
     *
     * Reported as a closed connection rather than as misuse, because from the caller's side that is
     * what it is: the session it holds no longer has one.
     */
    private fun checkOpen() {
        if (sessionClosed) throw NetworkException(
            NetworkExceptionReason.CONNECTION_CLOSED,
            details = "This session has been closed; its connection has gone back to the pool or been discarded",
            sqlState = "08003"
        )
    }

    /** Ties a log line to the backend behind this session; see the same property on [OctaviusConnection]. */
    private val pid: String get() = "[PID: ${octaviusConnection.stream.processId}]"

    override fun reloadTypes() {
        checkOpen()
        GlobalCatalogStore.reload(
            octaviusConnection.databaseKey,
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
     * Runs an operation that goes through the JDBC connection, in this API's own terms.
     *
     * Two kinds of `java.sql.SQLException` arrive here. The driver's own comes wrapped, and is
     * unwrapped back into the exception it started as. The rest belong to whatever stands between
     * this session and its connection: a pool's proxy answering for a connection it has taken back
     * or evicted, and reporting it as a bare `SQLException` with neither SQLState nor cause. Since
     * that is not a shape this API uses anywhere else, it is restated too - after a look through the
     * chain, in case the pool was carrying a driver failure it had wrapped on the way.
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

    // Answers rather than raises on a session already given up, the way JDBC has `isValid` answer
    // for a closed connection - a health check is the one question a dead session can still settle.
    override fun isValid(timeout: Int): Boolean = !sessionClosed && rawConnection.isValid(timeout)

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

    /**
     * Undoes the per-session state this session left on its connection, so the next borrower of
     * a pooled connection finds it as it was: any `LISTEN` registrations, and a transaction
     * opened by a hand-written `BEGIN`.
     *
     * Only reachable from [close]. Code that hands the connection back some other way - Spring's
     * `DataSourceUtils`, for instance - never closes the session and so never runs this.
     */
    private fun resetConnectionState() {
        notifications.releaseSubscriptions()
        rollbackTransactionTheDriverNeverOpened()
    }

    /**
     * Undoes a transaction the driver did not open, which in practice means one started by a
     * hand-written `BEGIN`.
     *
     * With auto-commit on, neither this driver nor the pool believes a transaction exists, so
     * nothing else would clean it up and the connection would go back carrying somebody's
     * uncommitted work - which the next borrower could then commit without ever knowing. The
     * transaction status comes from the server's own `ReadyForQuery`, so noticing this costs
     * nothing; only actually finding one costs a round trip.
     *
     * It is rolled back rather than committed on purpose: the driver has no idea what that
     * work was, and discarding it is the recoverable mistake.
     */
    private fun rollbackTransactionTheDriverNeverOpened() {
        if (!autoCommit) return // a real manual transaction; the pool resets those itself
        if (transactionState == TransactionState.IDLE) return
        octaviusConnection.queryExecutor.execute("ROLLBACK")
    }

    override fun abort() {
        // Set before the abort rather than after: from here on the connection is on its way out of
        // the pool whatever happens below, so nothing more should be asked of this session even if
        // the abort itself raises.
        sessionClosed = true
        try {
            rawConnection.abort(OctaviusDispatchers.VirtualExecutor)
        } catch (_: SQLExceptionWrapper) {
            // Expected, and not a failure: aborting throws on purpose, that being the signal a
            // pool needs in order to evict the connection instead of taking it back. The abort
            // itself is already reported by the connection, so logging here would put a stack
            // trace under every routine teardown - a cancelled listener loop aborts on its way
            // out - and say nothing the line above it did not.
        } catch (e: Exception) {
            // Anything that is not the driver's own wrapper did not come from the abort protocol: a
            // rejected executor, a pool proxy in a state of its own. Then the connection really
            // was left as it stood, and nothing else records that.
            logger.debug(e) { "$pid Aborting the session raised" }
        }
    }

    override fun close() {
        if (sessionClosed) return
        try {
            if (octaviusConnection.isClosed || octaviusConnection.stream.isBroken) {
                // If the underlying connection is already flagged as closed/broken,
                // we force an abort on the pool connection to evict it from the pool.
                abort()
            } else if (octaviusConnection.stream.copyInProgress) {
                // A transfer the caller never finished. Ending it here is the wrong trade: a
                // COPY OUT ends only once the rest of the export has been read and discarded,
                // and this runs on whoever gave the session back - a `finally`, or a pool
                // reclaiming its connection - so an export abandoned on its first chunk would
                // hold that thread for as long as the server needs to produce every remaining
                // row. Evicting is bounded and loses nothing the cancel would have kept: a COPY
                // IN that never reached endCopy() commits nothing either way.
                logger.warn { "$pid Session closed with a COPY still in progress; the connection is aborted rather than reset" }
                abort()
            } else {
                // These outlive the session on a pooled connection, so they are undone here
                // instead of being left for whoever borrows it next: any LISTEN registrations
                // this session made, and a transaction opened by hand-written SQL.
                try {
                    resetConnectionState()
                } catch (e: Exception) {
                    // The connection cannot go back to the pool carrying this session's leftovers,
                    // so it is evicted instead - which the pool reports as a connection lost for
                    // no visible reason unless this line says why.
                    logger.warn(e) { "$pid Could not reset connection state on close; aborting the connection instead" }
                    abort()
                    return
                }
                // Only where the session is what the connection was opened for. Where somebody else
                // owns it, giving it back here would return a connection its owner still counts as
                // borrowed - and a pool would then hand the same connection to two callers.
                if (ownsConnection) rawConnection.close()
            }
        } catch (e: Exception) {
            logger.debug(e) { "$pid Closing the session raised" }
        } finally {
            // Last, not first: everything above still has to be able to speak to the connection,
            // and the reset in particular runs statements on it.
            sessionClosed = true
        }
    }
}
