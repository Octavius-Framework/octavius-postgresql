package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.session.OctaviusSession
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.ResourceHolderSupport
import org.springframework.transaction.support.ResourceHolderSynchronization
import javax.sql.DataSource

/**
 * What an [OctaviusSession] is bound to the current transaction under.
 *
 * A data class so that two [OctaviusTemplate] instances over the same `DataSource` look up the same
 * session rather than opening one each over the connection they share.
 */
internal data class OctaviusSessionKey(val dataSource: DataSource)

/**
 * Holds the session bound to the current transaction.
 *
 * Spring drives the connection itself inside a transaction, so a session over it lasts as long as
 * the transaction does rather than as long as one [OctaviusTemplate.execute] call: opening one per
 * call would build a notification, copy and type manager each time, and would leave the question of
 * which of them undoes the state on the connection unanswered.
 */
internal class OctaviusSessionHolder(val session: OctaviusSession) : ResourceHolderSupport()

/**
 * Ends the transaction-scoped session at the right point in Spring's completion sequence.
 *
 * `ResourceHolderSynchronization` carries the binding for us, including `suspend` and `resume` - a
 * `REQUIRES_NEW` transaction inside another one gets a different connection, and without unbinding
 * over the suspension it would find the outer transaction's session still bound and run its work on
 * the wrong connection.
 *
 * @property springTransaction Whether Spring began a transaction the session runs in, rather than
 * synchronization alone - `PROPAGATION_SUPPORTS` with no transaction to join.
 */
internal class OctaviusSessionSynchronization(
    holder: OctaviusSessionHolder,
    key: OctaviusSessionKey,
    private val springTransaction: Boolean
) : ResourceHolderSynchronization<OctaviusSessionHolder, OctaviusSessionKey>(holder, key) {

    /**
     * Ahead of Spring's own connection synchronization, which releases the connection at
     * [DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER]. Ending the session runs statements on that
     * connection, so it has to come first.
     */
    override fun getOrder(): Int = DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER - 1

    /**
     * Moves the release of a session in a transaction Spring began from `beforeCompletion`, where
     * this class puts it by default, to `afterCompletion`.
     *
     * `beforeCompletion` runs ahead of the rollback, so on the failing path the transaction is still
     * failed - and PostgreSQL ignores every statement until the block ends, with `25P02`. The reset
     * would raise there, and a session that cannot reset its connection gives the connection up
     * instead: every transaction that failed on the server would cost one, measured as an eviction
     * and a new backend on the next borrow. `afterCompletion` runs once the commit or rollback is
     * through and the connection answers again, and still before Spring releases it.
     *
     * Where Spring began nothing there is no commit or rollback to wait for, and its connection
     * synchronization gives the connection back already in `beforeCompletion`, nothing holding it
     * open. The session ends there, ahead of it.
     */
    override fun shouldReleaseBeforeCompletion(): Boolean = !springTransaction

    override fun releaseResource(resourceHolder: OctaviusSessionHolder, resourceKey: OctaviusSessionKey) {
        // Closes the session, not the connection: the session was opened over a connection Spring
        // owns, and Spring releases it in the step after this one. A transaction Spring began is over
        // by now, so one still open is one nobody began - a pool's with auto-commit off.
        resourceHolder.session.closeRollingBack()
    }
}

/**
 * Closes the session after rolling back the transaction a pool with auto-commit off leaves open on its
 * connection - for a session no Spring transaction is running under any more.
 *
 * Such a session leaves a transaction it found its connection in to the connection's owner, and here
 * nobody else ends it: Spring began nothing, and HikariCP rolls back on return only work that went
 * through its own statements. Left alone, the next borrower would carry on inside it and commit it.
 *
 * A connection that will not take the rollback is given up rather than back.
 */
internal fun OctaviusSession.closeRollingBack() {
    try {
        // With auto-commit on there is nothing the pool left open; a hand-written BEGIN the session ends itself.
        if (!autoCommit) rollback()
    } catch (_: Exception) {
        abort()
        return
    }
    close()
}
