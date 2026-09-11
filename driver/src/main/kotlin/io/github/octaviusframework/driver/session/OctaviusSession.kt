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

/**
 * Defines a set of core operations that can be executed on an active database session.
 * These operations do not include explicit transaction lifecycle management (commit, rollback, auto-commit).
 */
interface OctaviusSessionOperations {

    /**
     * Manages PostgreSQL types and their OIDs, providing functionality for registration and resolution.
     */
    val typeManager: TypeManager

    /**
     * Handles asynchronous PostgreSQL notifications (`LISTEN` / `NOTIFY` mechanism).
     */
    val notifications: NotificationManager

    /**
     * Provides an API for performing PostgreSQL COPY operations.
     */
    val copy: CopyManager

    /**
     * Provides an API for managing PostgreSQL Large Objects.
     */
    val largeObjects: LargeObjectManager

    /**
     * Provides a high-level API for executing operations within transaction scopes 
     * (e.g., `required`, `nested`). Automatically manages commits and rollbacks 
     * based on block execution outcomes.
     */
    val transaction: TransactionManager

    /**
     * Reloads the database types and updates internal OID mappings.
     */
    fun reloadTypes()

    /**
     * Creates a native query from the provided SQL string using standard PostgreSQL 
     * positional parameters (e.g. `$1`, `$2`).
     *
     * @param sql The SQL statement to prepare.
     * @return A newly created [NativeQuery].
     */
    fun createNativeQuery(sql: String): NativeQuery

    /**
     * Creates a query with named parameters (e.g., `@name`, `@id`) which will be translated
     * into positional parameters under the hood.
     *
     * @param sql The SQL statement containing named parameters.
     * @return A newly created [NamedParameterQuery].
     */
    fun createNamedQuery(sql: String): NamedParameterQuery

    /**
     * Attempts to cancel the currently executing query on this session.
     */
    fun cancelQuery()

    /**
     * The schemas making up this session's current `search_path`.
     *
     * Read from what the server reported rather than queried for: PostgreSQL 18 announces
     * `search_path` in `ParameterStatus` and announces it again whenever it moves, so a
     * hand-written `SET search_path` mid-session shows up here without a round trip.
     */
    val searchPath: List<String>

    /**
     * Manually aborts the connection, forcing the underlying connection pool (like HikariCP)
     * to evict it instead of returning it to the pool.
     *
     * The session is finished afterwards, on the same terms as one that has been closed: anything
     * asked of it from here raises [NetworkException][io.github.octaviusframework.driver.exception.NetworkException].
     */
    fun abort()

    /**
     * The current transaction state of this session (Idle, In Transaction, or Failed).
     */
    val transactionState: TransactionState

    /**
     * The transaction isolation level for this session.
     *
     * The session's, and only the session's: a level asked for through [TransactionManager.required] belongs
     * to that one transaction and is not readable here, so this can answer `READ COMMITTED` while a
     * `SERIALIZABLE` block is running.
     */
    var transactionIsolationLevel: TransactionIsolationLevel

    /**
     * Indicates whether this session is currently in read-only mode.
     *
     * The session's, on the same terms as [transactionIsolationLevel].
     */
    var readOnly: Boolean

    /**
     * The network timeout (in milliseconds) for operations executed on this session.
     */
    var networkTimeout: Int

    /**
     * Checks if this session is still valid and the underlying connection is alive.
     *
     * A session that has been closed or aborted answers `false` rather than raising, the way JDBC
     * has the same question answered for a closed connection.
     *
     * @param timeout The maximum time in seconds to wait for a database response.
     * @return `true` if the session is valid, `false` otherwise.
     */
    fun isValid(timeout: Int): Boolean
}

/**
 * Represents an active database session extending standard operations with 
 * full lifecycle control and transaction management.
 */
interface OctaviusSession : OctaviusSessionOperations, AutoCloseable {

    /**
     * Ends the session, undoing the per-session state it left on its connection and giving that
     * connection up - back to the pool it was borrowed from, or closed outright.
     *
     * The session is finished at that point and holds no claim on the connection, which a pool may
     * already have handed to somebody else. Anything asked of it from here raises
     * [NetworkException][io.github.octaviusframework.driver.exception.NetworkException] rather than
     * reaching a connection that is no longer this session's; closing twice does nothing the second
     * time.
     */
    override fun close()

    /**
     * Specifies whether the session operates in auto-commit mode.
     * If `true`, each individual statement is treated as a separate transaction.
     *
     * Switching it back on commits the transaction that is open, and is refused on the same terms as
     * [commit] where an earlier error aborted that transaction.
     */
    var autoCommit: Boolean

    /**
     * Commits the current transaction, persisting all changes made within it.
     *
     * A transaction an earlier error aborted is not committed and not quietly rolled back either. PostgreSQL
     * would answer the `COMMIT` with a `ROLLBACK` and report nothing, so the driver does not send it: the
     * transaction stays [FAILED][TransactionState.FAILED], and [rollback] is the way out.
     *
     * @throws io.github.octaviusframework.driver.exception.InvalidOperationException `AUTO_COMMIT_VIOLATION`
     * where auto-commit is on, `COMMIT_OF_FAILED_TRANSACTION` where an earlier error aborted the transaction.
     */
    fun commit()

    /**
     * Rolls back the current transaction, discarding all changes made within it.
     */
    fun rollback()

    /**
     * Creates an unnamed savepoint within the current transaction and returns it.
     *
     * @return The created [OctaviusSavepoint].
     */
    fun setSavepoint(): OctaviusSavepoint

    /**
     * Creates a named savepoint within the current transaction and returns it.
     *
     * @param name The specific name to assign to the savepoint.
     * @return The created [OctaviusSavepoint].
     */
    fun setSavepoint(name: String): OctaviusSavepoint

    /**
     * Rolls back the transaction to the state at the time the given savepoint was created.
     *
     * @param savepoint The savepoint to roll back to.
     */
    fun rollback(savepoint: OctaviusSavepoint)

    /**
     * Releases the specified savepoint from the current transaction.
     *
     * @param savepoint The savepoint to release.
     */
    fun releaseSavepoint(savepoint: OctaviusSavepoint)
}

/**
 * Represents the current state of a database transaction.
 */
enum class TransactionState {
    /**
     * Session is idle, no active transaction block.
     */
    IDLE,

    /**
     * Session is inside an active transaction block.
     */
    IN_TRANSACTION,

    /**
     * Session is inside a failed transaction block, awaiting a `ROLLBACK`.
     */
    FAILED;

    companion object {
        /**
         * Converts the single-character protocol response into a [TransactionState].
         *
         * @param c The character code representing the transaction state ('I', 'T', or 'E').
         * @return The corresponding [TransactionState].
         * @throws IllegalStateException If an unknown state character is provided.
         */
        fun fromChar(c: Char): TransactionState = when (c) {
            'I' -> IDLE
            'T' -> IN_TRANSACTION
            'E' -> FAILED
            else -> error("Unknown transaction state: $c")
        }
    }
}

/**
 * Defines the available transaction isolation levels.
 *
 * @property jdbcValue The `java.sql.Connection.TRANSACTION_*` constant for this level, which is also
 * Spring's `ISOLATION_*` constant for it.
 * @property sqlName How the level is spelled in `SET TRANSACTION ISOLATION LEVEL` and in `BEGIN`.
 */
enum class TransactionIsolationLevel(val jdbcValue: Int, val sqlName: String) {
    READ_UNCOMMITTED(1, "READ UNCOMMITTED"),
    READ_COMMITTED(2, "READ COMMITTED"),
    REPEATABLE_READ(4, "REPEATABLE READ"),
    SERIALIZABLE(8, "SERIALIZABLE");

    companion object {
        /**
         * Maps a JDBC isolation level integer to the corresponding [TransactionIsolationLevel].
         *
         * @param level The JDBC isolation level constant.
         * @return The matching [TransactionIsolationLevel].
         * @throws InvalidOperationException `INVALID_ARGUMENT` where [level] is not one of the four - which
         * is what `setTransactionIsolation` raises for the same value going the other way.
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
