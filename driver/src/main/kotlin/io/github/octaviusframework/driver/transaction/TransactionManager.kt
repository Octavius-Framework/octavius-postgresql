package io.github.octaviusframework.driver.transaction

import io.github.octaviusframework.driver.jdbc.OctaviusConnection
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.driver.session.TransactionIsolationLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Runs blocks in transaction scopes that commit, roll back or use savepoints on how the block ends. The block's
 * receiver is [OctaviusSessionOperations], which has no commit or rollback; those are on [OctaviusSession].
 */
class TransactionManager internal constructor(
    @PublishedApi internal val session: OctaviusSession,
    private val connection: OctaviusConnection
) {

    /**
     * Runs [block] in the transaction already open, or in a new one that commits when it returns and rolls back
     * when it throws. Leave the block with `return@required`; a plain `return` does not compile.
     *
     * The terms belong to this transaction alone and go out with the block's first statement, at no round trip
     * of their own; one the server refuses fails that statement. Joining a running transaction ignores them,
     * with a warning.
     *
     * @param isolation `null` for the session's.
     * @param statementTimeout Aborts any single statement running longer than this.
     * @param transactionTimeout Aborts the transaction once it has been open longer than this.
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
        var failure: Throwable? = null
        try {
            // Inside the try: after a hand-written BEGIN the terms go out here, and a refusal has to roll back.
            applySettings(isolation, readOnly, statementTimeout, transactionTimeout)
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
     * Runs [block] under a savepoint in the transaction already open, released when it returns and rolled back to
     * when it throws; with none open, as [required]. Leave the block with `return@nested`. The terms apply only to
     * a new transaction; under a savepoint they are ignored with a warning.
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
     * Hands the terms to the transaction's `BEGIN` as one script of `SET TRANSACTION` and `SET LOCAL`, which end
     * with the transaction. Sent at once where a transaction the driver did not begin is already running.
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
            // A running transaction_timeout clock ignores a new value; 0 stops it and the value starts it again.
            transactionTimeout?.let {
                add("SET LOCAL transaction_timeout = 0")
                add("SET LOCAL transaction_timeout = ${it.inWholeMilliseconds}")
            }
        }

        connection.deferTransactionTerms(statements.joinToString("; "))
    }

    /**
     * Warns that a scope asked for terms it cannot set. Not raised, since an inner unit of work often states a
     * level its caller already provides.
     *
     * @param scope What is entered instead: a running transaction, or a savepoint.
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
     * Puts auto-commit back once a scope is over. A failure is suppressed on [failure] where the scope failed, and
     * only logged after a commit: the work is in the database, and raising would invite a retry that writes it
     * twice. The next use of the session raises anyway.
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