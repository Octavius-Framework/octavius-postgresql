package io.github.octaviusframework.driver.transaction

import io.github.octaviusframework.driver.jdbc.OctaviusConnection
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * A high-level API for managing transactions via scoped blocks.
 * 
 * This manager provides robust block-based transaction scoping, such as 
 * [required] and [nested], automatically handling commit, rollback, and 
 * savepoints based on the execution result. Within these scopes, the receiver 
 * is restricted to [OctaviusSessionOperations], preventing manual interference 
 * with the transaction lifecycle.
 * 
 * If you need manual, low-level transaction control (e.g., explicit `commit()`, 
 * `rollback()`, `autoCommit` manipulation, or manual savepoints), use the methods 
 * provided directly on the parent [OctaviusSession].
 */
class TransactionManager internal constructor(
    @PublishedApi internal val session: OctaviusSession,
    private val connection: OctaviusConnection
) {

    /**
     * Executes the given [block] within a transaction scope.
     * 
     * If a transaction is currently active (autoCommit = false), the block will be
     * executed within the existing transaction. Otherwise, a new transaction is started,
     * and it will be committed upon successful completion, or rolled back if an exception occurs.
     * 
     * Inside the block, manual transaction operations such as `commit`, `rollback`, 
     * and `autoCommit` modifications are not accessible as the receiver is restricted
     * to [OctaviusSessionOperations]. A `return` out of the block does not compile either - use
     * `return@required`, since an early exit would otherwise commit whatever the block had already
     * done, which is the same decision the restricted receiver is there to keep out of the block.
     *
     * Whatever of [isolation], [readOnly], [statementTimeout] and [transactionTimeout] is asked for goes out
     * in the same message as the `BEGIN`, which the block's first statement sends ahead of itself - at no
     * round trip of their own, and not at all where the block runs no statement. A term the server refuses
     * fails that first statement, inside the block, so it rolls back like any other failure. All four end
     * with the transaction, which is what separates them from
     * [OctaviusSessionOperations.transactionIsolationLevel] and [OctaviusSessionOperations.readOnly] - those
     * are session-wide, and are not what this sets.
     *
     * A transaction already running keeps the terms it began at: none of the four applies, and a warning
     * names them.
     *
     * @param isolation The isolation level for this transaction, or `null` for the session's.
     * @param readOnly Whether this transaction refuses writes.
     * @param statementTimeout Aborts any single statement in this transaction that runs longer than this.
     * @param transactionTimeout Aborts this transaction once it has been open longer than this.
     * @param block The block of code to execute.
     * @return The result of the block.
     */
    inline fun <T> required(
        isolation: TransactionIsolationLevel? = null,
        readOnly: Boolean = false,
        statementTimeout: Duration? = null,
        transactionTimeout: Duration? = null,
        crossinline block: OctaviusSessionOperations.() -> T
    ): T {
        if (!session.autoCommit) {
            warnSettingsIgnored(isolation, readOnly, statementTimeout, transactionTimeout, "a transaction that is already running")
            return session.block()
        }

        session.autoCommit = false
        applySettings(isolation, readOnly, statementTimeout, transactionTimeout)
        var failure: Throwable? = null
        try {
            val result = session.block()
            session.commit()
            return result
        } catch (e: Throwable) {
            failure = e
            try {
                session.rollback()
            } catch (rollbackFailure: Throwable) {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        } finally {
            restoreAutoCommit(failure)
        }
    }

    /**
     * Executes the given [block] within a nested transaction scope.
     * 
     * If a transaction is already active, a savepoint is created. Upon successful
     * completion of the block, the savepoint is released. If an exception occurs,
     * the transaction is rolled back to the savepoint. If no transaction is active,
     * a new one is started similar to [required].
     * 
     * Inside the block, manual transaction operations are not accessible as the receiver
     * is restricted to [OctaviusSessionOperations]. A `return` out of the block does not compile
     * either - use `return@nested`, since an early exit would otherwise slip past the release and
     * the rollback alike and leave the savepoint standing.
     *
     * The four terms reach only the case where this starts a transaction of its own; on the savepoint path
     * they are ignored and a warning names them.
     *
     * @param isolation See [required].
     * @param readOnly See [required].
     * @param statementTimeout See [required].
     * @param transactionTimeout See [required].
     * @param block The block of code to execute.
     * @return The result of the block.
     */
    inline fun <T> nested(
        isolation: TransactionIsolationLevel? = null,
        readOnly: Boolean = false,
        statementTimeout: Duration? = null,
        transactionTimeout: Duration? = null,
        crossinline block: OctaviusSessionOperations.() -> T
    ): T {
        if (!session.autoCommit) {
            warnSettingsIgnored(isolation, readOnly, statementTimeout, transactionTimeout, "a savepoint")
            val sp = session.setSavepoint()
            try {
                val result = session.block()
                session.releaseSavepoint(sp)
                return result
            } catch (e: Throwable) {
                try {
                    session.rollback(sp)
                } catch (rollbackFailure: Throwable) {
                    e.addSuppressed(rollbackFailure)
                }
                throw e
            }
        }

        return required(isolation, readOnly, statementTimeout, transactionTimeout, block)
    }

    /**
     * Hands the terms this transaction was opened with to its `BEGIN`, as one statement.
     *
     * `SET TRANSACTION` for the isolation level and the read-only flag, `SET LOCAL` for the timeouts: both
     * end with the transaction, so nothing here has to be undone before the connection goes back to a pool.
     * The first of them has to precede the first query of the transaction, and nothing precedes it more
     * closely than the `BEGIN`'s own message - a script, so one round trip carries it all, with nothing to bind.
     *
     * Sent now only where the server already has a transaction running that no `BEGIN` of the driver's opened.
     */
    @PublishedApi
    internal fun applySettings(
        isolation: TransactionIsolationLevel?,
        readOnly: Boolean,
        statementTimeout: Duration?,
        transactionTimeout: Duration?
    ) {
        if (isolation == null && !readOnly && statementTimeout == null && transactionTimeout == null) return

        val statements = buildList {
            if (isolation != null || readOnly) {
                add(
                    buildString {
                        append("SET TRANSACTION")
                        if (isolation != null) append(" ISOLATION LEVEL ${isolation.sqlName}")
                        if (readOnly) append(" READ ONLY")
                    }
                )
            }
            statementTimeout?.let { add("SET LOCAL statement_timeout = ${it.inWholeMilliseconds}") }
            transactionTimeout?.let { add("SET LOCAL transaction_timeout = ${it.inWholeMilliseconds}") }
        }

        connection.deferTransactionTerms(statements.joinToString("; "))
    }

    /**
     * Says out loud that a scope asked for terms it is in no position to set.
     *
     * Raising would be the stricter reading, and would also break the ordinary case: an inner unit of work
     * states the level it needs and is called from an outer one that already provides it.
     *
     * @param scope What is being entered instead - a transaction already running, or a savepoint.
     */
    @PublishedApi
    internal fun warnSettingsIgnored(
        isolation: TransactionIsolationLevel?,
        readOnly: Boolean,
        statementTimeout: Duration?,
        transactionTimeout: Duration?,
        scope: String
    ) {
        if (isolation == null && !readOnly && statementTimeout == null && transactionTimeout == null) return
        logger.warn {
            "Entering $scope, so isolation=$isolation, readOnly=$readOnly, statementTimeout=$statementTimeout " +
                "and transactionTimeout=$transactionTimeout are ignored; the terms already in force stand. " +
                "A block that needs its own terms needs its own transaction."
        }
    }

    /**
     * Puts auto-commit back once a scope is over, however it ended.
     *
     * A failure here is only rethrown when the scope was already failing, and then as a suppressed
     * exception on [failure] rather than in its place. Out of a scope that **committed** it is logged
     * and goes no further. What it commits there is the empty transaction the successful `commit()`
     * left open behind it, which the server has no reason to refuse - so what fails is the socket,
     * and a broken socket is already on the record: `checkClosed` raises `NetworkException` the next
     * time anything touches this session, logged or not. Raising it here would only report a failed
     * transaction for one whose work is in the database, and invite a retry that writes it twice.
     */
    @PublishedApi
    internal fun restoreAutoCommit(failure: Throwable?) {
        try {
            session.autoCommit = true
        } catch (restoreFailure: Throwable) {
            if (failure != null) {
                failure.addSuppressed(restoreFailure)
            } else {
                logger.warn(restoreFailure) {
                    "Transaction committed, but auto-commit could not be restored - this session is no longer safe to reuse"
                }
            }
        }
    }
}