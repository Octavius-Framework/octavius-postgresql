package io.github.octaviusframework.driver.exception

import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException

/**
 * The driver's exception as the [java.sql.SQLException] the JDBC surface throws, carrying its SQLSTATE; the stack
 * trace is [wrappedException]'s. A [SessionTerminatedException] gets a next exception with SQLSTATE `08006`, so a
 * pool evicts the connection at once.
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

/** Runs [block], wrapping the driver's exceptions in a [SQLExceptionWrapper]. */
internal inline fun <T> wrapSqlException(block: () -> T): T {
    try {
        return block()
    } catch (e: OctaviusException) {
        throw SQLExceptionWrapper(e)
    }
}

/** The `08006` a pool evicts on, chained behind a terminated session's wrapper. No stack trace of its own. */
private class ConnectionClosedByServer(terminated: SessionTerminatedException) : SQLNonTransientConnectionException(
    "The server ended the session (${terminated.sqlState}), and the connection is closed: ${terminated.dbMessage}",
    "08006"
) {
    override fun fillInStackTrace(): Throwable = this
}
