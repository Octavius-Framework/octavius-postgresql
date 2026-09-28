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

import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.findOctaviusCause
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.driver.spring.exception.OctaviusExceptionTranslator
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.support.SQLExceptionTranslator
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Template class that simplifies executing Octavius operations and provides proper integration
 * with Spring's transaction management and exception translation mechanism.
 *
 * @property dataSource the data source used to obtain connections
 * @property exceptionTranslator the translator used to convert SQLExceptions into Spring's DataAccessException hierarchy
 */
class OctaviusTemplate(private val dataSource: DataSource, val exceptionTranslator: SQLExceptionTranslator = OctaviusExceptionTranslator()) {

    /** What a transaction-scoped session is bound under; equal across templates over one data source. */
    private val sessionKey = OctaviusSessionKey(dataSource)

    /**
     * Executes the given action within an [OctaviusSession], translating any exceptions thrown.
     * The session is passed as the receiver of [action], so its operations are available directly.
     * Connection management and transaction synchronization are handled automatically.
     *
     * @param action the action to execute, with the session as its receiver
     * @return the result of the action
     * @throws org.springframework.dao.DataAccessException if a database access error occurs or an exception is translated
     */
    fun <T> execute(action: OctaviusSessionOperations.() -> T): T {
        // Acquisition is translated too, and restated as the driver's exception first, so a pool
        // timeout or a refused connection arrives as an OctaviusDataAccessException rather than as
        // the pool's own exception.
        val con = try {
            DataSourceUtils.doGetConnection(dataSource)
        } catch (ex: SQLException) {
            throw unobtainable(connectionRefusal(ex, dataSource))
        } catch (ex: RuntimeException) {
            // A HikariDataSource made with its no-argument constructor, as Spring Boot makes it,
            // starts its pool on the first borrow. When that fails with the driver's exception -
            // unchecked, not a SQLException - Hikari throws its own PoolInitializationException.
            throw unobtainable(ex.findOctaviusCause() ?: throw ex)
        }

        var session: OctaviusSession? = null
        var transactionScoped = false
        try {
            val bound = TransactionSynchronizationManager.getResource(sessionKey) as OctaviusSessionHolder?
            if (bound != null) {
                session = bound.session
                transactionScoped = true
            } else {
                // Never owning: the connection came from DataSourceUtils and goes back the same way.
                session = con.getOctaviusSession(ownsConnection = false)
                transactionScoped = bindToTransaction(session)
            }
            return session.action()
        } catch (ex: SQLException) {
            throw translate("OctaviusTemplate execution", ex)
        } catch (ex: RuntimeException) {
            // Covers the driver's own exceptions, which are runtime exceptions, and anything that
            // wrapped one on its way here; anything else is left as it is.
            throw ex.findOctaviusCause()?.let { OctaviusDataAccessException(it) } ?: ex
        } finally {
            // Outside a transaction the session ends with the call that opened it, undoing the
            // LISTEN registrations and any hand-written BEGIN before the connection goes back. A
            // transaction-scoped one is ended by its synchronization instead, once, at the end -
            // closing it here would reset a connection the transaction is still working on.
            if (!transactionScoped) session?.close()
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
        TransactionSynchronizationManager.registerSynchronization(OctaviusSessionSynchronization(holder, sessionKey))
        return true
    }

    /**
     * Hands a failure to obtain a connection to the [exceptionTranslator] in the shape every other
     * driver failure reaches it in, so a translator of your own still sees it.
     *
     * @param failure the driver's exception, found in the cause chain or restated from the data source's refusal
     * @return the translated exception, ready to be thrown
     */
    private fun unobtainable(failure: OctaviusException): DataAccessException =
        translate("OctaviusTemplate connection acquisition", SQLExceptionWrapper(failure))

    /**
     * Runs [ex] through the configured [exceptionTranslator], falling back to [UncategorizedSQLException]
     * when it declines to translate.
     *
     * @param task readable text describing the task being attempted
     * @param ex the offending SQLException
     * @return the translated exception, ready to be thrown
     */
    private fun translate(task: String, ex: SQLException): DataAccessException =
        exceptionTranslator.translate(task, null, ex) ?: UncategorizedSQLException(task, null, ex)
}
