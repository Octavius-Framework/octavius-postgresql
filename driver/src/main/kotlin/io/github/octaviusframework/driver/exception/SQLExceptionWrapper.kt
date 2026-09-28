package io.github.octaviusframework.driver.exception

import java.sql.SQLException

/**
 * A wrapper class that adapts an [OctaviusException] into a standard [java.sql.SQLException].
 *
 * This wrapper is necessary for integration with standard JDBC components like connection pools
 * (e.g., HikariCP) which rely on intercepting [java.sql.SQLException] to analyze connection state,
 * evict dead connections, and properly interpret the `SQLState`.
 *
 * It records no stack trace: the one that says where the failure happened is [wrappedException]'s.
 *
 * @property wrappedException The original [OctaviusException] that is being wrapped.
 */
class SQLExceptionWrapper(val wrappedException: OctaviusException) :
    SQLException(wrappedException.message, wrappedException.sqlState) {

    override fun fillInStackTrace(): Throwable = this
}