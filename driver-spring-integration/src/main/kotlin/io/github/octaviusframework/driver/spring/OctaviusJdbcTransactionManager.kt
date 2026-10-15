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

// Not `logger`: inside the class that name is the Commons Logging field Spring's transaction managers carry,
// which takes a plain Object - a lambda handed to it is logged as the lambda, not as its message.
private val log = KotlinLogging.logger {}

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
 * It also enforces the transaction's timeout, which `JdbcTransactionManager` leaves to `JdbcTemplate`
 * and which would otherwise do nothing here: `@Transactional(timeout = 5)` is sent to the server when
 * the transaction begins, as the setting [timeoutScope] names - see [TransactionTimeoutScope] for
 * what each one costs.
 *
 * Register it in place of [JdbcTransactionManager]; [OctaviusSpringAutoConfiguration] does when the
 * application declares no transaction manager of its own.
 */
open class OctaviusJdbcTransactionManager(dataSource: DataSource) : JdbcTransactionManager(dataSource) {

    /**
     * What a transaction's timeout is enforced on: each statement, and a connection that survives one
     * running out, or the transaction as a whole, and a connection that does not.
     * [TransactionTimeoutScope.STATEMENT] unless set - see [TransactionTimeoutScope].
     *
     * Only a transaction with a timeout is affected. One without - no `timeout` on the annotation and no
     * `defaultTimeout` on this manager - sends nothing, and runs under whatever the server is configured
     * with.
     */
    var timeoutScope: TransactionTimeoutScope = TransactionTimeoutScope.STATEMENT

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

    /**
     * Sends the transaction's timeout, where it has one, as the setting [timeoutScope] names.
     *
     * `SET LOCAL`, so it ends with the transaction and nothing follows the connection back into the pool. Only a
     * transaction this manager begins gets one - Spring applies a timeout to no other, so a method that joins a
     * running transaction or takes a savepoint keeps the terms already in force.
     *
     * Being a statement, it is also what sends the transaction's `BEGIN`, which the driver otherwise holds back
     * until the first statement of the method: a transaction with a timeout begins here, at a round trip for the
     * `BEGIN` and one for the setting. One without sends nothing here, and keeps waiting.
     */
    override fun prepareTransactionalConnection(con: Connection, definition: TransactionDefinition) {
        super.prepareTransactionalConnection(con, definition)
        val timeout = determineTimeout(definition)
        if (timeout == TransactionDefinition.TIMEOUT_DEFAULT) return
        // Spring's seconds in PostgreSQL's milliseconds, and never 0: to PostgreSQL that is no limit at all,
        // where to Spring it is a deadline already reached.
        val millis = (timeout * 1000L).coerceAtLeast(1)
        con.createStatement().use { it.execute("SET LOCAL ${timeoutScope.setting} = $millis") }
    }

    override fun doCommit(status: DefaultTransactionStatus) {
        departedConnection(status)?.let { throw OctaviusDataAccessException(it) }
        super.doCommit(status)
    }

    override fun doRollback(status: DefaultTransactionStatus) {
        val departed = departedConnection(status)
        if (departed != null) {
            log.debug { "Rollback on a connection that has already gone; the server discarded the transaction with it" }
            return
        }
        try {
            super.doRollback(status)
        } catch (ex: RuntimeException) {
            // A connection can look present to its pool and be gone underneath: a session the server ended
            // while it sat idle is found by the next thing sent on it, and here that is the ROLLBACK. Failing
            // to send it leaves the pool holding a connection it has evicted, and the rollback just as
            // satisfied as above - so it is not what the caller hears either.
            if (departedConnection(status) == null) throw ex
            log.debug(ex) { "Rollback found the connection already gone; the server discarded the transaction with it" }
        }
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
