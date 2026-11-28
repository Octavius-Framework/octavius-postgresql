package io.github.octaviusframework.driver.transaction

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.execution.QueryExecutor
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.octaviusframework.driver.session.TransactionState
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Savepoint

private val logger = KotlinLogging.logger {}

/**
 * The transaction state of one physical connection: auto-commit and the `BEGIN` it leaves to the first statement,
 * read only, isolation and savepoints. Changes arrive through the JDBC `Connection`, which a pool's proxy stands in
 * front of.
 */
internal class TransactionControl(private val stream: PgStream, private val queryExecutor: QueryExecutor) {

    /** The backend process id every log line carries, as `pg_stat_activity` and the server's log show it. */
    private val pid: String get() = "[PID: ${stream.processId}]"

    //----------------------------------------------------READ ONLY-----------------------------------------------------
    private var readOnlyFlag: Boolean = false

    val readOnly: Boolean get() = readOnlyFlag

    fun setReadOnly(readOnly: Boolean) {
        if (readOnlyFlag != readOnly) {
            val modeStr = if (readOnly) "READ ONLY" else "READ WRITE"
            val query = buildString {
                append("SET SESSION CHARACTERISTICS AS TRANSACTION $modeStr")
                // Only a transaction the server has begun. One whose BEGIN is still waiting picks the session
                // characteristic up when that BEGIN goes out.
                if (serverState == TransactionState.IN_TRANSACTION) {
                    append("; SET TRANSACTION $modeStr")
                }
            }
            queryExecutor.control(query)
            readOnlyFlag = readOnly
            logger.debug { "$pid Session characteristics set to $modeStr" }
        }
    }

    //---------------------------------------------------TRANSACTIONS---------------------------------------------------
    private var autoCommitFlag: Boolean = true

    private var isolationLevel: TransactionIsolationLevel = TransactionIsolationLevel.READ_COMMITTED

    val autoCommit: Boolean get() = autoCommitFlag

    val isolation: TransactionIsolationLevel get() = isolationLevel

    /** The server's transaction state, with a `BEGIN` still waiting counted as `IN_TRANSACTION`. */
    val state: TransactionState
        get() {
            val reported = serverState
            return if (reported == TransactionState.IDLE && !autoCommitFlag) TransactionState.IN_TRANSACTION else reported
        }

    /** The transaction state as the server reported it on its last `ReadyForQuery`, a waiting `BEGIN` not counted. */
    val serverState: TransactionState
        get() = TransactionState.fromChar(stream.transactionStatus)

    /**
     * Switching off sends nothing, leaving `BEGIN` to the first statement; switching on commits a transaction a
     * statement began.
     */
    fun setAutoCommit(autoCommit: Boolean) {
        if (autoCommitFlag != autoCommit) {
            if (autoCommit) refuseCommitOfFailedTransaction()
            autoCommitFlag = autoCommit
            queryExecutor.beginsTransactions = !autoCommit
            queryExecutor.deferredTerms = null
            if (!autoCommit) {
                logger.debug { "$pid Auto-commit disabled; the first statement begins the transaction" }
            } else if (serverState == TransactionState.IDLE) {
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
    fun deferTerms(terms: String) {
        if (!autoCommitFlag && serverState == TransactionState.IDLE) {
            queryExecutor.deferredTerms = terms
        } else {
            queryExecutor.execute(terms)
        }
    }

    /**
     * Commits and leaves the next `BEGIN` to the next statement, so its `now()` is not fixed here. A transaction no
     * statement began ends with nothing sent.
     */
    fun commit() {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        refuseCommitOfFailedTransaction()
        endTransaction("COMMIT")
    }

    /** Rolls back, on the same terms as [commit]. */
    fun rollback() {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION)
        endTransaction("ROLLBACK")
    }

    /** Raises `COMMIT_OF_FAILED_TRANSACTION` where an earlier error aborted the transaction. */
    private fun refuseCommitOfFailedTransaction() {
        if (state == TransactionState.FAILED) {
            throw InvalidOperationException(InvalidOperationExceptionReason.COMMIT_OF_FAILED_TRANSACTION)
        }
    }

    /** Sends [command] where a statement began a transaction, and drops the terms a waiting `BEGIN` held. */
    private fun endTransaction(command: String) {
        queryExecutor.deferredTerms = null
        if (serverState == TransactionState.IDLE) {
            logger.debug { "$pid $command with no statement since the last; nothing had begun, so nothing sent" }
            return
        }
        queryExecutor.control(command)
        logger.debug { "$pid $command sent; the next statement begins the next transaction" }
    }

    fun setIsolation(level: TransactionIsolationLevel) {
        val query = buildString {
            append("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL ${level.sqlName}")
            // As in setReadOnly: a waiting BEGIN picks the session characteristic up by itself.
            if (serverState == TransactionState.IN_TRANSACTION) {
                append("; SET TRANSACTION ISOLATION LEVEL ${level.sqlName}")
            }
        }
        queryExecutor.control(query)
        isolationLevel = level
        logger.debug { "$pid Isolation level set to ${level.sqlName}" }
    }

    //----------------------------------------------------SAVEPOINTS----------------------------------------------------
    private var savepointIdCounter: Int = 1

    fun setSavepoint(): Savepoint {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot set a savepoint when auto-commit is enabled")
        val sp = OctaviusSavepointImpl(savepointIdCounter++)
        queryExecutor.execute("SAVEPOINT ${sp.pgName}")
        logger.debug { "$pid Savepoint ${sp.pgName} set" }
        return sp
    }

    fun setSavepoint(name: String?): Savepoint {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot set a savepoint when auto-commit is enabled")
        if (name == null) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Savepoint name cannot be null")
        val sp = OctaviusSavepointImpl(name)
        queryExecutor.execute("SAVEPOINT ${sp.pgName}")
        logger.debug { "$pid Savepoint ${sp.pgName} set" }
        return sp
    }

    fun rollback(savepoint: Savepoint?) {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot rollback to a savepoint when auto-commit is enabled")
        if (savepoint !is OctaviusSavepointImpl) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Unsupported savepoint")
        queryExecutor.control("ROLLBACK TO SAVEPOINT ${savepoint.pgName}")
        logger.debug { "$pid Rolled back to savepoint ${savepoint.pgName}" }
    }

    fun releaseSavepoint(savepoint: Savepoint?) {
        if (autoCommitFlag) throw InvalidOperationException(InvalidOperationExceptionReason.AUTO_COMMIT_VIOLATION, "Cannot release a savepoint when auto-commit is enabled")
        if (savepoint !is OctaviusSavepointImpl) throw InvalidOperationException(InvalidOperationExceptionReason.INVALID_SAVEPOINT, "Unsupported savepoint")
        queryExecutor.control("RELEASE SAVEPOINT ${savepoint.pgName}")
        logger.debug { "$pid Savepoint ${savepoint.pgName} released" }
    }
}
