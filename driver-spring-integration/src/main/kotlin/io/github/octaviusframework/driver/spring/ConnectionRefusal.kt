package io.github.octaviusframework.driver.spring

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.exception.findOctaviusCause
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/**
 * The driver's exception for a connection [dataSource] would not hand over: the one in [ex]'s cause
 * chain where the data source was carrying a driver failure, and otherwise an [InitializationException]
 * restating the refusal - `CONNECTION_UNAVAILABLE` for a transient one, which is how a pool reports
 * having none free, and `CONNECTION_ERROR` for the rest.
 *
 * The rule `DataSource.getOctaviusSession()` applies, shared by the two places this module takes a
 * connection: [OctaviusTemplate] and [OctaviusJdbcTransactionManager].
 */
internal fun connectionRefusal(ex: SQLException, dataSource: DataSource): OctaviusException =
    ex.findOctaviusCause() ?: InitializationException(
        if (ex is SQLTransientConnectionException) InitializationExceptionReason.CONNECTION_UNAVAILABLE
        else InitializationExceptionReason.CONNECTION_ERROR,
        "Could not obtain a connection from ${dataSource.javaClass.name}: ${ex.message}",
        ex
    )
