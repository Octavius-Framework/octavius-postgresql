package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.NetworkException
import io.github.octaviusframework.driver.exception.NetworkExceptionReason
import io.github.octaviusframework.driver.exception.SQLExceptionWrapper
import io.github.octaviusframework.driver.exception.findOctaviusCause
import io.github.octaviusframework.driver.spring.exception.OctaviusDataAccessException
import io.github.octaviusframework.driver.spring.exception.OctaviusExceptionTranslator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionStatus
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

private val logger = KotlinLogging.logger {}

/**
 * A [JdbcTransactionManager] that tells the truth about a transaction whose connection is already
 * gone.
 *
 * A connection can leave in the middle of a transaction: the driver aborts one whose stream is
 * broken or that was left mid-`COPY`, and `session.abort()` does it on request. Whichever way, the
 * pool evicts it and its proxy answers everything afterwards with a bare `java.sql.SQLException`
 * carrying neither SQLState nor cause - so there is nothing left for an exception translator to
 * recognise, and Spring's own handling turns both the commit and the rollback into
 * `TransactionSystemException: JDBC commit failed`.
 *
 * That matters most on the rollback path, where the rollback runs because something already went
 * wrong: Spring logs `Application exception overridden by rollback exception` and the failure the
 * caller needed to see is replaced by one about the cleanup.
 *
 * The two paths are not symmetrical and are not treated as such:
 *
 * - **Commit must raise.** The commit did not happen, and saying nothing would be a claim about
 *   durability that is not true.
 * - **Rollback must not.** It is already satisfied - the server discarded the transaction along
 *   with the connection - so raising over the top of it buries the real exception to report a
 *   cleanup step that had nothing left to do.
 *
 * A transaction that never got a connection is reported as the driver's exception too - see
 * [doBegin].
 *
 * Register it in place of [JdbcTransactionManager]; [OctaviusSpringAutoConfiguration] does when the
 * application declares no transaction manager of its own.
 */
open class OctaviusJdbcTransactionManager(dataSource: DataSource) : JdbcTransactionManager(dataSource) {

    init {
        // A failure raised while the manager itself is committing or rolling back arrives in
        // Spring's DataAccessException hierarchy rather than as a raw SQLException.
        exceptionTranslator = OctaviusExceptionTranslator()
        // What makes @Transactional(propagation = NESTED) resolve to a savepoint.
        isNestedTransactionAllowed = true
    }

    /**
     * Reports a transaction that could not get its connection the way [OctaviusTemplate] reports a
     * block that could not: as the driver's exception, through the exception translator - by default
     * an [OctaviusDataAccessException] over an `InitializationException`, `CONNECTION_UNAVAILABLE` where
     * the pool had none free.
     *
     * Spring's own `doBegin` wraps every failure in `CannotCreateTransactionException`, which an
     * `@ExceptionHandler` for `OctaviusDataAccessException` does not catch. Nothing in Spring's
     * transaction handling keys off that type - a failure to begin is handled as any runtime exception
     * - so it is not kept.
     *
     * Everything `doBegin` does after taking the connection goes through the driver, so a
     * `SQLException` with nothing of the driver's in it is the data source refusing one. A failure
     * that is neither leaves as Spring raised it.
     */
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        try {
            super.doBegin(transaction, definition)
        } catch (ex: CannotCreateTransactionException) {
            val failure = when (val cause = ex.cause) {
                is SQLException -> connectionRefusal(cause, obtainDataSource())
                // Hikari's PoolInitializationException, when this borrow was the one that started the pool.
                else -> cause?.findOctaviusCause() ?: throw ex
            }
            throw translateException("JDBC begin", SQLExceptionWrapper(failure))
        }
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        departedConnection(status)?.let { throw OctaviusDataAccessException(it) }
        super.doCommit(status)
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        val departed = departedConnection(status)
        if (departed != null) {
            logger.debug { "Rollback on a connection that has already gone; the server discarded the transaction with it" }
            return
        }
        super.doRollback(status)
    }

    /**
     * The failure to report for this transaction's connection, or `null` where the connection is
     * still there and Spring should go ahead.
     *
     * `isClosed()` is the one question a pool's evicted proxy still answers - HikariCP's returns
     * `true` without raising, where every other call raises - which is what makes this checkable
     * before the commit rather than only after it has failed.
     */
    private fun departedConnection(status: DefaultTransactionStatus): NetworkException? {
        if (!status.hasTransaction()) return null
        val transaction = status.transaction as? JdbcTransactionObjectSupport ?: return null
        if (!transaction.hasConnectionHolder()) return null

        val connection: Connection = try {
            transaction.connectionHolder.connection
        } catch (_: IllegalStateException) {
            // No connection held to judge; whatever happens next is Spring's to report.
            return null
        }

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
