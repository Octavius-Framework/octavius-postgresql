package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.octaviusframework.driver.transaction.OctaviusSavepoint
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.SavepointManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.ResourceTransactionManager
import org.springframework.transaction.support.SmartTransactionObject
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionSynchronizationUtils
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

private val kotlinLogger = KotlinLogging.logger {}

/**
 * A transaction manager for a `DataSource` that runs each transaction through an [OctaviusSession], so a
 * failure of the database or the connection leaves it as the driver's own exception.
 *
 * The session is opened when the transaction begins and closed when it ends, and every
 * [OctaviusTemplate.execute] inside the transaction runs through it.
 *
 * On a connection that is already gone a commit raises and a rollback stays quiet; so do releasing a
 * `Propagation.NESTED` savepoint and rolling back to it.
 *
 * A commit that raises is rolled back after, whatever `rollbackOnCommitFailure` says - that setting covers a
 * `TransactionException` only - so synchronizations hear `STATUS_ROLLED_BACK`, even where the connection went
 * while the `COMMIT` waited and the server may have committed.
 *
 * `@Transactional(timeout = ...)` is enforced on the server, as [timeoutScope] names.
 *
 * [OctaviusSpringAutoConfiguration] registers it when the application declares no transaction manager of its own.
 *
 * @property dataSource The data source whose connections the transactions run on.
 */
open class OctaviusJdbcTransactionManager(val dataSource: DataSource) :
    AbstractPlatformTransactionManager(), ResourceTransactionManager {

    /**
     * What a transaction's timeout is enforced on, [TransactionTimeoutScope.STATEMENT] unless set. A transaction
     * without a timeout - none on the annotation, no `defaultTimeout` here - sends nothing.
     */
    var timeoutScope: TransactionTimeoutScope = TransactionTimeoutScope.STATEMENT

    /** What the session of a running transaction is bound under, which is where [OctaviusTemplate] looks first. */
    private val sessionKey = OctaviusSessionKey(dataSource)

    init {
        // What makes @Transactional(propagation = NESTED) resolve to a savepoint.
        isNestedTransactionAllowed = true
    }

    override fun getResourceFactory(): Any = dataSource

    /** Picks up the session of a transaction this manager is running; one [OctaviusTemplate] bound is not one. */
    override fun doGetTransaction(): Any =
        OctaviusTransaction(TransactionSynchronizationManager.getResource(sessionKey) as? OctaviusTransactionHolder)

    override fun isExistingTransaction(transaction: Any): Boolean = (transaction as OctaviusTransaction).holder != null

    /**
     * Takes a connection, opens the session over it, begins the transaction, and binds the session to the thread.
     * A connection the data source would not hand over comes out as the driver's exception, as from
     * [OctaviusTemplate], not as Spring's `CannotCreateTransactionException`.
     */
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        val tx = transaction as OctaviusTransaction
        val con = try {
            dataSource.connection
        } catch (ex: Exception) {
            // A SQLException, or Hikari's PoolInitializationException when this borrow was the one that started the pool.
            throw connectionRefusal(ex, dataSource)
        }

        var holder: OctaviusTransactionHolder? = null
        try {
            val session = con.getOctaviusSession(ownsConnection = false)
            holder = OctaviusTransactionHolder(session, con)

            // Only what the connection does not already have, so that only that is put back: a pool that hands out
            // read-only connections gets them back read-only.
            if (definition.isReadOnly && !session.readOnly) {
                session.readOnly = true
                holder.resetReadOnly = true
            }
            if (definition.isolationLevel != TransactionDefinition.ISOLATION_DEFAULT) {
                val level = TransactionIsolationLevel.fromJdbcValue(definition.isolationLevel)
                val previous = session.transactionIsolationLevel
                if (previous != level) {
                    session.transactionIsolationLevel = level
                    holder.previousIsolationLevel = previous
                }
            }
            if (session.autoCommit) {
                session.autoCommit = false
                holder.mustRestoreAutoCommit = true
            }
            prepareTransactionalSession(session, definition)

            tx.holder = holder
            TransactionSynchronizationManager.bindResource(sessionKey, holder)
            TransactionSynchronizationManager.bindResource(dataSource, holder.connectionHolder)
        } catch (ex: Throwable) {
            if (holder == null) {
                DataSourceUtils.releaseConnection(con, dataSource)
            } else {
                // Still bound only if the last step, binding the connection, was refused: another manager's
                // transaction holds the DataSource, and its binding is left as it is.
                if (TransactionSynchronizationManager.getResource(sessionKey) === holder) {
                    TransactionSynchronizationManager.unbindResource(sessionKey)
                }
                // A timeout sent its BEGIN ahead of itself, and nobody is going to end that transaction.
                try {
                    if (!holder.session.autoCommit) holder.session.rollback()
                } catch (rollbackFailure: Exception) {
                    ex.addSuppressed(rollbackFailure)
                }
                release(holder)
            }
            tx.holder = null
            throw ex
        }
    }

    /**
     * Sends the transaction's timeout, where it has one, as `SET LOCAL` of the setting [timeoutScope] names, so
     * nothing follows the connection back to the pool. Called only for a transaction this manager begins, never
     * for one a method joins or a savepoint. A statement sent here also sends the transaction's `BEGIN`, which
     * the driver otherwise holds back until the method's first statement.
     */
    protected open fun prepareTransactionalSession(session: OctaviusSession, definition: TransactionDefinition) {
        val timeout = determineTimeout(definition)
        if (timeout == TransactionDefinition.TIMEOUT_DEFAULT) return
        // Spring's seconds in PostgreSQL's milliseconds, and never 0: to PostgreSQL that is no limit at all,
        // where to Spring it is a deadline already reached.
        val millis = (timeout * 1000L).coerceAtLeast(1)
        val sql = when (timeoutScope) {
            TransactionTimeoutScope.STATEMENT -> "SET LOCAL statement_timeout = $millis"
            // The server starts the transaction's clock with whatever transaction_timeout is in force - a database
            // or role default - and a later value leaves a running clock as it is. 0 stops it, and this starts it again.
            TransactionTimeoutScope.TRANSACTION -> "SET LOCAL transaction_timeout = 0; SET LOCAL transaction_timeout = $millis"
        }
        session.createNativeQuery(sql).execute()
    }

    override fun doSuspend(transaction: Any): Any {
        (transaction as OctaviusTransaction).holder = null
        TransactionSynchronizationManager.unbindResource(dataSource)
        return TransactionSynchronizationManager.unbindResource(sessionKey)
    }

    override fun doResume(transaction: Any?, suspendedResources: Any) {
        val holder = suspendedResources as OctaviusTransactionHolder
        TransactionSynchronizationManager.bindResource(sessionKey, holder)
        TransactionSynchronizationManager.bindResource(dataSource, holder.connectionHolder)
    }

    /** Commits, or raises `NetworkException(CONNECTION_ABORTED)` for a transaction whose connection is already gone. */
    override fun doCommit(status: DefaultTransactionStatus) {
        val tx = status.transaction as OctaviusTransaction
        tx.departedConnection()?.let { throw it }
        try {
            tx.session.commit()
        } catch (ex: OctaviusException) {
            throw tx.reported(ex)
        }
    }

    /** Rolls back, and stays quiet for a transaction whose connection is already gone. */
    override fun doRollback(status: DefaultTransactionStatus) {
        val tx = status.transaction as OctaviusTransaction
        if (tx.departedConnection() == null) {
            try {
                tx.session.rollback()
                return
            } catch (ex: OctaviusException) {
                // A session the server ended while it sat idle is found by the ROLLBACK itself, and the
                // transaction is as gone as above, so the caller does not hear of it either.
                if (tx.departedConnection() == null) throw ex
            }
        }
        kotlinLogger.debug { "Rollback on a connection that has already gone; the server discarded the transaction with it" }
    }

    override fun doSetRollbackOnly(status: DefaultTransactionStatus) {
        (status.transaction as OctaviusTransaction).holder!!.setRollbackOnly()
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        TransactionSynchronizationManager.unbindResourceIfPossible(sessionKey)
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource)
        release((transaction as OctaviusTransaction).holder!!)
    }

    /** Puts back what the transaction changed on the connection, closes the session, and gives the connection back to the pool. */
    private fun release(holder: OctaviusTransactionHolder) {
        val session = holder.session
        try {
            if (holder.mustRestoreAutoCommit) session.autoCommit = true
            holder.previousIsolationLevel?.let { session.transactionIsolationLevel = it }
            if (holder.resetReadOnly) session.readOnly = false
        } catch (ex: Exception) {
            kotlinLogger.debug(ex) { "Could not reset the connection after the transaction" }
        }
        // Undoes what the transaction's blocks left on the connection, LISTEN registrations among them; the
        // connection stays open for the pool.
        session.close()
        DataSourceUtils.releaseConnection(holder.connection, dataSource)
        holder.clear()
    }
}

/** The session of a transaction [OctaviusJdbcTransactionManager] began, with what to put back on its connection at the end. */
private class OctaviusTransactionHolder(session: OctaviusSession, val connection: Connection) : OctaviusSessionHolder(session) {

    /**
     * [connection], bound under the `DataSource` where `DataSourceUtils` looks, so that Spring's own script runner -
     * `@Sql` in a `@Transactional` test, a `ResourceDatabasePopulator` - runs inside the transaction rather than on
     * a connection of its own, committing as it goes.
     */
    val connectionHolder = ConnectionHolder(connection)

    /** Whether the transaction switched auto-commit off, and so switches it back on at the end. */
    var mustRestoreAutoCommit: Boolean = false

    var previousIsolationLevel: TransactionIsolationLevel? = null

    var resetReadOnly: Boolean = false
}

/**
 * The transaction object of [OctaviusJdbcTransactionManager]: the transaction's session, and the savepoints of
 * `Propagation.NESTED` taken through it.
 *
 * A savepoint asked for in a transaction already marked rollback-only is refused the way Spring refuses it,
 * with `CannotCreateTransactionException`.
 */
private class OctaviusTransaction(var holder: OctaviusTransactionHolder?) : SavepointManager, SmartTransactionObject {

    val session: OctaviusSession get() = holder!!.session

    /** The savepoint rolled back to on a connection already gone, whose release Spring sends next. */
    private var discarded: Any? = null

    // Spring reads this to roll back a transaction a participant marked, rather than commit it.
    override fun isRollbackOnly(): Boolean = holder?.isRollbackOnly == true

    override fun flush() = TransactionSynchronizationUtils.triggerFlush()

    override fun createSavepoint(): Any {
        if (isRollbackOnly) throw CannotCreateTransactionException("Cannot create savepoint for transaction which is already marked as rollback-only")
        departedConnection()?.let { throw it }
        return try {
            session.setSavepoint()
        } catch (ex: OctaviusException) {
            throw reported(ex)
        }
    }

    /**
     * Rolls back to the savepoint, and stays quiet when the connection is gone: the server discarded the
     * savepoint with the transaction, and the caller needs the exception that caused the rollback. Either way it
     * clears the rollback-only mark a method that joined inside the savepoint left, as Spring does.
     */
    override fun rollbackToSavepoint(savepoint: Any) {
        if (departedConnection() == null) {
            try {
                session.rollback(savepoint as OctaviusSavepoint)
                holder!!.resetRollbackOnly()
                return
            } catch (ex: OctaviusException) {
                // A session the server ended while it sat idle is found by the ROLLBACK TO itself.
                if (departedConnection() == null) throw ex
            }
        }
        kotlinLogger.debug { "Rollback to a savepoint on a connection that has already gone; the server discarded it with the transaction" }
        holder!!.resetRollbackOnly()
        discarded = savepoint
    }

    /**
     * Releases the savepoint, which is how a nested method that returned normally ends, and raises
     * `NetworkException(CONNECTION_ABORTED)` when the connection is gone: what the method did is gone with it.
     */
    override fun releaseSavepoint(savepoint: Any) {
        if (savepoint === discarded) {
            discarded = null
            return
        }
        departedConnection()?.let { throw it }
        try {
            session.releaseSavepoint(savepoint as OctaviusSavepoint)
        } catch (ex: OctaviusException) {
            throw reported(ex)
        }
    }

    /**
     * [ex], or the connection being gone where [ex] is a pool's proxy answering for a connection that went while
     * the call was on its way: a bare `SQLException`, which the session restates as `CONNECTION_ERROR` with it as
     * the cause.
     */
    fun reported(ex: OctaviusException): OctaviusException =
        if (ex.cause is SQLException) departedConnection() ?: ex else ex

    /**
     * The failure to report for this transaction's connection, or `null` where the connection is
     * still there and the transaction can go on.
     *
     * `isClosed()` is the one question a pool's evicted proxy still answers - HikariCP's returns
     * `true` without raising, where every other call raises - which is what makes this checkable
     * before the commit rather than only after it has failed.
     */
    fun departedConnection(): NetworkException? {
        val connection = holder?.connection ?: return null
        val closed = try {
            connection.isClosed
        } catch (_: SQLException) {
            // One that will not say whether it is closed is not one to commit on.
            true
        }
        if (!closed) return null

        return NetworkException(
            NetworkExceptionReason.CONNECTION_ABORTED,
            details = "The connection was aborted or closed before the transaction completed, so the transaction went with it",
            sqlState = "08003"
        )
    }
}
