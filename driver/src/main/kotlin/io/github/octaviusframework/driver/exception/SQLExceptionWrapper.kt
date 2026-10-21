package io.github.octaviusframework.driver.exception

import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException

/**
 * A wrapper class that adapts an [OctaviusException] into a standard [java.sql.SQLException].
 *
 * This wrapper is necessary for integration with standard JDBC components like connection pools
 * (e.g., HikariCP) which rely on intercepting [java.sql.SQLException] to analyze connection state,
 * evict dead connections, and properly interpret the `SQLState`.
 *
 * It records no stack trace: the one that says where the failure happened is [wrappedException]'s.
 *
 * A [SessionTerminatedException] keeps the server's SQLSTATE here, and most of those codes - `25P04`,
 * `57P05` - are not ones a pool reads as a lost connection. So its wrapper chains a next exception in the
 * class a pool does read, `08006`, the way a driver reports the I/O failure that followed a `FATAL`: the
 * first exception says why, the next that the connection is gone, and a pool evicts it at once rather than
 * at its next validation.
 *
 * @property wrappedException The original [OctaviusException] that is being wrapped.
 */
class SQLExceptionWrapper(val wrappedException: OctaviusException) :
    SQLException(wrappedException.message, wrappedException.sqlState) {

    init {
        if (wrappedException is SessionTerminatedException) {
            setNextException(ConnectionClosedByServer(wrappedException))
        }
    }

    override fun fillInStackTrace(): Throwable = this
}

/**
 * Runs [block], restating any of the driver's exceptions it raises in a [SQLExceptionWrapper] - how every method the
 * JDBC surface implements fails, where what it does not implement throws the driver's exception as it is.
 */
internal inline fun <T> wrapSqlException(block: () -> T): T {
    try {
        return block()
    } catch (e: OctaviusException) {
        throw SQLExceptionWrapper(e)
    }
}

/**
 * The half of a terminated session a connection pool acts on, chained behind the wrapper that carries it.
 *
 * Without a stack trace for the wrapper's reason: a pool evicting over this logs it, and its frames would
 * say nothing the wrapped exception does not.
 */
private class ConnectionClosedByServer(terminated: SessionTerminatedException) : SQLNonTransientConnectionException(
    "The server ended the session (${terminated.sqlState}), and the connection is closed: ${terminated.dbMessage}",
    "08006"
) {
    override fun fillInStackTrace(): Throwable = this
}
