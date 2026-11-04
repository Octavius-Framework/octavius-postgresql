/*
 *                      ____   _____ _______  __      _______ _    _  _____
 *                     / __ \ / ____|__   __|/\ \    / /_   _| |  | |/ ____|
 *                    | |  | | |       | |  /  \ \  / /  | | | |  | | (___
 *                    | |  | | |       | | / /\ \ \/ /   | | | |  | |\___ \
 *                    | |__| | |____   | |/ ____ \  /   _| |_| |__| |____) |
 *                     \____/ \_____|  |_/_/    \_\/   |_____|\____/|_____/
 *                   --------------------------------------------------------
 *                                        OCTAVIUS DRIVER
 *                   --------------------------------------------------------
 */
package io.github.octaviusframework.driver.session

import io.github.octaviusframework.driver.copy.CopyManager
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.lo.LargeObjectManager
import io.github.octaviusframework.driver.notification.NotificationManager
import io.github.octaviusframework.driver.query.NamedParameterQuery
import io.github.octaviusframework.driver.query.NativeQuery
import io.github.octaviusframework.driver.transaction.OctaviusSavepoint
import io.github.octaviusframework.driver.transaction.TransactionManager
import io.github.octaviusframework.driver.registry.TypeManager

/** What a session offers apart from its transaction lifecycle: no commit, rollback or auto-commit. */
interface OctaviusSessionOperations {

    /** Registers converters and resolves types on this session's database. */
    val typeManager: TypeManager

    /** `LISTEN` and `NOTIFY`. */
    val notifications: NotificationManager

    /** `COPY` in and out. */
    val copy: CopyManager

    /** Large objects. */
    val largeObjects: LargeObjectManager

    /** Runs blocks in transaction scopes, committing or rolling back on how they end. */
    val transaction: TransactionManager

    /** Re-reads the type catalog of this session's database. */
    fun reloadTypes()

    /** A query with positional parameters, `$1`, `$2`. */
    fun createNativeQuery(sql: String): NativeQuery

    /** A query with named parameters, `@name`. */
    fun createNamedQuery(sql: String): NamedParameterQuery

    /** Asks the server to cancel the statement running on this session. */
    fun cancelQuery()

    /**
     * The schemas the current `search_path` lists, as the server reads them: unquoted names in lower case,
     * `$user` and `pg_temp` as they stand. Follows a `SET search_path` without a round trip.
     *
     * This is the setting, not the lookup order, which adds `pg_catalog` and resolves `$user`.
     */
    val searchPath: List<String>

    /**
     * Aborts the connection, so a pool evicts it instead of reusing it. Anything asked of the session afterwards
     * raises [NetworkException][io.github.octaviusframework.driver.exception.NetworkException].
     */
    fun abort()

    /** The transaction state. With auto-commit off it is `IN_TRANSACTION` before the first statement too. */
    val transactionState: TransactionState

    /**
     * The session's isolation level. A level given to [TransactionManager.required] belongs to that transaction
     * and does not show here.
     */
    var transactionIsolationLevel: TransactionIsolationLevel

    /** Whether the session is read-only, on the same terms as [transactionIsolationLevel]. */
    var readOnly: Boolean

    /** The network timeout, in milliseconds. */
    var networkTimeout: Int

    /** Whether the connection answers within [timeout] seconds. A closed or aborted session answers `false`. */
    fun isValid(timeout: Int): Boolean
}

/** A session with its transaction lifecycle: commit, rollback, auto-commit and savepoints. */
interface OctaviusSession : OctaviusSessionOperations, AutoCloseable {

    /**
     * Undoes the session's state on its connection and gives the connection up, back to its pool or closed.
     * Anything asked of the session afterwards raises
     * [InvalidOperationException][io.github.octaviusframework.driver.exception.InvalidOperationException]
     * `RESOURCE_CLOSED`; a second close does nothing.
     */
    override fun close()

    /**
     * Whether each statement is its own transaction. Switching it off sends nothing: `BEGIN` goes out ahead of
     * the next statement. Switching it on commits the open transaction, and is refused as [commit] is.
     */
    var autoCommit: Boolean

    /**
     * Commits the current transaction; the next statement begins another. One that ran no statement ends with
     * nothing sent. One an earlier error aborted is not committed and stays [FAILED][TransactionState.FAILED]
     * until [rollback].
     *
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `AUTO_COMMIT_VIOLATION`
     * where auto-commit is on, `COMMIT_OF_FAILED_TRANSACTION` where an earlier error aborted the transaction.
     */
    fun commit()

    /** Rolls back the current transaction; the next statement begins another. */
    fun rollback()

    /** Sets an unnamed savepoint in the current transaction. */
    fun setSavepoint(): OctaviusSavepoint

    /** Sets a savepoint named [name] in the current transaction. */
    fun setSavepoint(name: String): OctaviusSavepoint

    /** Rolls the transaction back to [savepoint]. */
    fun rollback(savepoint: OctaviusSavepoint)

    /** Releases [savepoint]. */
    fun releaseSavepoint(savepoint: OctaviusSavepoint)
}

/** The transaction state the server last reported. */
enum class TransactionState {
    /** No transaction. */
    IDLE,

    /** In a transaction. */
    IN_TRANSACTION,

    /** In a transaction an error aborted, which only a rollback ends. */
    FAILED;

    companion object {
        /** The state for a `ReadyForQuery` status byte: `I`, `T` or `E`. */
        fun fromChar(c: Char): TransactionState = when (c) {
            'I' -> IDLE
            'T' -> IN_TRANSACTION
            'E' -> FAILED
            else -> error("Unknown transaction state: $c")
        }
    }
}

/**
 * The transaction isolation levels.
 *
 * @property jdbcValue The `java.sql.Connection.TRANSACTION_*` constant, which is also Spring's `ISOLATION_*` one.
 * @property sqlName The level as `BEGIN` and `SET TRANSACTION ISOLATION LEVEL` spell it.
 */
enum class TransactionIsolationLevel(val jdbcValue: Int, val sqlName: String) {
    READ_UNCOMMITTED(1, "READ UNCOMMITTED"),
    READ_COMMITTED(2, "READ COMMITTED"),
    REPEATABLE_READ(4, "REPEATABLE READ"),
    SERIALIZABLE(8, "SERIALIZABLE");

    companion object {
        /**
         * The level for a JDBC `TRANSACTION_*` constant.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` for any other value.
         */
        fun fromJdbcValue(level: Int): TransactionIsolationLevel {
            return entries.firstOrNull { it.jdbcValue == level }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unsupported transaction isolation level: $level"
                )
        }
    }
}
