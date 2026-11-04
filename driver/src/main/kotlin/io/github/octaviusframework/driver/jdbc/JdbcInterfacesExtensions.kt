package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.findOctaviusCause
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/**
 * Runs [block], which gets a connection from [from], and raises what fails as the driver's exception: the one
 * in the cause chain where there is one, else an [InitializationException] over what was raised.
 */
private inline fun <T> obtainingSession(from: Any, block: () -> T): T {
    try {
        return block()
    } catch (e: Exception) {
        throw e.findOctaviusCause() ?: InitializationException(
            // A pool's borrow that timed out is transient, and worth retrying; failing to open one is not.
            if (e is SQLTransientConnectionException) InitializationExceptionReason.CONNECTION_UNAVAILABLE
            else InitializationExceptionReason.CONNECTION_ERROR,
            "Could not obtain a session from ${from.javaClass.name}: ${e.message}",
            e
        )
    }
}

// DriverManager
/** Opens a session on a new connection to [url], with [properties] over what the URL states. */
fun getOctaviusSession(
    url: String,
    properties: OctaviusProperties
): OctaviusSession {
    val mergedProps = OctaviusProperties.parse(url)
    mergedProps.merge(properties)
    val conn = OctaviusConnectionFactory.createConnection(mergedProps)
    return OctaviusSessionImpl(conn)
}

/** Opens a session on a new connection configured by [properties]. */
fun getOctaviusSession(
    properties: OctaviusProperties
): OctaviusSession {
    val conn = OctaviusConnectionFactory.createConnection(properties)
    return OctaviusSessionImpl(conn)
}

/** Opens a session on a new connection to [url] as [user]. */
fun getOctaviusSession(
    url: String,
    user: String, password: String
): OctaviusSession {
    val props = OctaviusProperties.parse(url)
    props.user = user
    props.password = password
    val conn = OctaviusConnectionFactory.createConnection(props)
    return OctaviusSessionImpl(conn)
}

// DataSource
/** The [OctaviusDataSource] this data source wraps or is. */
fun DataSource.unwrapToOctavius(): OctaviusDataSource {
    return this.unwrap(OctaviusDataSource::class.java)
}

/** `unwrap(T::class.java)`. */
inline fun <reified T> DataSource.unwrap(): T {
    return this.unwrap(T::class.java)
}

/**
 * Opens a session on a connection from this data source; closing the session gives the connection back.
 *
 * @throws InitializationException if the data source would not give a connection, such as a pool that timed
 * out or was closed. Where the pool restated a failure of the driver's own, that one is raised instead.
 */
fun DataSource.getOctaviusSession(): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this.getConnection())
}

/**
 * [getOctaviusSession] with credentials for this call, which not every data source takes: HikariCP refuses them.
 *
 * @throws InitializationException as [getOctaviusSession] does.
 */
fun DataSource.getOctaviusSession(username: String, password: String): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this.getConnection(username, password))
}

// Connection
/**
 * Opens a session over this connection, which has to unwrap to an [OctaviusConnection]; one a pool already took
 * back is refused.
 *
 * @param ownsConnection Whether closing the session closes the connection. Pass `false` where something else
 * gives it back, such as Spring's `DataSourceUtils`: closing then only undoes the session's state, and a
 * transaction already open when the session began is left to the owner.
 * @throws InitializationException if a session could not be opened over this connection.
 */
fun Connection.getOctaviusSession(ownsConnection: Boolean = true): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this, ownsConnection)
}

/** `unwrap(T::class.java)`. */
inline fun <reified T : Any> Connection.unwrap(): T {
    return this.unwrap(T::class.java)
}
