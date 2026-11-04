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
 * Runs a block on an [OctaviusSession]: the running transaction's where there is one, otherwise one over a
 * connection borrowed for the call.
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
     * Runs [action] with the session as its receiver and returns what it returns.
     *
     * @throws io.github.octaviusframework.driver.exception.OctaviusException for a failure of the driver's,
     *   including a connection the data source would not hand over, which is an `InitializationException`.
     *   Anything else [action] throws leaves as it was thrown.
     */
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T {
        // The session of a transaction OctaviusJdbcTransactionManager began, or one an earlier call bound: whoever
        // bound it ends it, along with the transaction.
        val bound = TransactionSynchronizationManager.getResource(sessionKey) as OctaviusSessionHolder?
        if (bound != null) return bound.session.action()

        val con = try {
            DataSourceUtils.doGetConnection(dataSource)
        } catch (ex: Exception) {
            // A SQLException, or Hikari's PoolInitializationException when this borrow is the one that starts the pool.
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
            // Outside a transaction the session ends with the call, undoing LISTEN registrations and a
            // hand-written BEGIN before the connection goes back; a transaction-scoped one is ended by its
            // synchronization. A connection DataSourceTransactionManager bound without synchronization is
            // that transaction's to end, so its session only closes.
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
