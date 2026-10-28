/*
 *                      ____   _____ _______  __      _______ _    _  _____
 *                     / __ \ / ____|__   __|/\ \    / /_   _| |  | |/ ____|
 *                    | |  | | |       | |  /  \ \  / /  | | | |  | | (___
 *                    | |  | | |       | | / /\ \ \/ /   | | | |  | |\___ \
 *                    | |__| | |____   | |/ ____ \  /   _| |_| |__| |____) |
 *                     \____/ \_____|  |_/_/    \_\/   |_____|\____/|_____/
 *                   --------------------------------------------------------
 *                                  OCTAVIUS SPRING INTEGRATION
 *                   --------------------------------------------------------
 */
package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * Template class that simplifies executing Octavius operations and provides proper integration
 * with Spring's transaction management.
 *
 * Failures leave it as they leave a session used directly: as the driver's own exceptions, with
 * nothing of Spring's around them.
 *
 * @property dataSource the data source used to obtain connections
 */
class OctaviusTemplate(private val dataSource: DataSource) {

    /** What a transaction-scoped session is bound under; equal across templates over one data source. */
    private val sessionKey = OctaviusSessionKey(dataSource)

    /**
     * Executes the given action within an [OctaviusSession].
     * The session is passed as the receiver of [action], so its operations are available directly.
     * Connection management and transaction synchronization are handled automatically.
     *
     * @param action the action to execute, with the session as its receiver
     * @return the result of the action
     * @throws io.github.octaviusframework.driver.exception.OctaviusException for a failure of the driver's,
     *   including a connection the data source would not hand over, which is an `InitializationException`.
     *   Anything else [action] throws leaves as it was thrown.
     */
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T {
        // The session of a transaction OctaviusJdbcTransactionManager began, or one an earlier call bound: whoever
        // bound it ends it, along with the transaction.
        val bound = TransactionSynchronizationManager.getResource(sessionKey) as OctaviusSessionHolder?
        if (bound != null) return bound.session.action()

        // A connection the data source would not hand over is restated as the driver's exception,
        // so a pool timeout or a refused connection arrives as an InitializationException rather
        // than as the pool's own exception.
        val con = try {
            DataSourceUtils.doGetConnection(dataSource)
        } catch (ex: Exception) {
            // A SQLException, or unchecked: a HikariDataSource made with its no-argument constructor,
            // as Spring Boot makes it, starts its pool on the first borrow, and a pool that fails to
            // start throws its own PoolInitializationException.
            throw connectionRefusal(ex, dataSource)
        }

        var session: OctaviusSession? = null
        var transactionScoped = false
        try {
            // Never owning: the connection came from DataSourceUtils and goes back the same way.
            session = con.getOctaviusSession(ownsConnection = false)
            transactionScoped = bindToTransaction(session)
            return session.action()
        } finally {
            // Outside a transaction the session ends with the call that opened it, undoing the
            // LISTEN registrations and any hand-written BEGIN before the connection goes back. A
            // transaction-scoped one is ended by its synchronization instead, once, at the end -
            // closing it here would reset a connection the transaction is still working on.
            // Without synchronization Spring's DataSourceTransactionManager still binds the connection
            // it began a transaction on, and that transaction is Spring's to end; nothing else binds one.
            if (!transactionScoped) {
                if (DataSourceUtils.isConnectionTransactional(con, dataSource)) session?.close() else session?.closeRollingBack()
            }
            DataSourceUtils.releaseConnection(con, dataSource)
        }
    }

    /**
     * Binds [session] to the current transaction, so that every later [execute] within it works
     * through the same session and the state it leaves is undone once, at completion.
     *
     * @return whether there was a transaction to bind to.
     */
    private fun bindToTransaction(session: OctaviusSession): Boolean {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false

        val holder = OctaviusSessionHolder(session)
        TransactionSynchronizationManager.bindResource(sessionKey, holder)
        TransactionSynchronizationManager.registerSynchronization(
            OctaviusSessionSynchronization(holder, sessionKey, TransactionSynchronizationManager.isActualTransactionActive())
        )
        return true
    }
}
