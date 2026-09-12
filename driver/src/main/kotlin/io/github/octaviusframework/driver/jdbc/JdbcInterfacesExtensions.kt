package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.findOctaviusCause
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSessionImpl
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import javax.sql.DataSource

/**
 * Runs the acquisition of a session over [from], restating anything it raises as the driver's own.
 *
 * Getting a session is the one point where this API asks a foreign object for a connection, and
 * whatever that object is answers in `java.sql.SQLException` - a pool reporting a borrow that timed
 * out, a pool that has been closed, one declining to forward per-call credentials. None of those is
 * a shape the session API uses anywhere else, so none of them is passed on as it stands.
 *
 * The driver's own failure is preferred over the restatement wrapped around it whenever the chain
 * carries one: a pool that could not reach the server reports a timeout, while the exception
 * underneath says the connection was refused, which is the half worth keeping.
 */
private inline fun <T> obtainingSession(from: Any, block: () -> T): T {
    try {
        return block()
    } catch (e: SQLException) {
        throw e.findOctaviusCause() ?: InitializationException(
            // Having none to give and failing to open one are different failures, and only the first is
            // worth retrying. JDBC has a class for exactly that distinction - which a pool reporting a
            // borrow that timed out raises - so it is read rather than guessed at from the message.
            if (e is SQLTransientConnectionException) InitializationExceptionReason.CONNECTION_UNAVAILABLE
            else InitializationExceptionReason.CONNECTION_ERROR,
            "Could not obtain a session from ${from.javaClass.name}: ${e.message}",
            e
        )
    }
}

// DriverManager
/**
 * Establishes a new [OctaviusSession] using the specified [url] and [properties] properties.
 *
 * @param url A database url of the form `jdbc:subprotocol:subname`.
 * @param properties A list of arbitrary string tag/value pairs as connection arguments.
 * @return An [OctaviusSession] instance.
 */
fun getOctaviusSession(
    url: String,
    properties: OctaviusProperties
): OctaviusSession {
    val mergedProps = OctaviusProperties.parse(url)
    mergedProps.merge(properties)
    val conn = OctaviusConnectionFactory.createConnection(mergedProps)
    return OctaviusSessionImpl(conn)
}

/**
 * Establishes a new [OctaviusSession] using the specified [properties].
 *
 * @param properties Connection arguments encapsulated in [OctaviusProperties].
 * @return An [OctaviusSession] instance.
 */
fun getOctaviusSession(
    properties: OctaviusProperties
): OctaviusSession {
    val conn = OctaviusConnectionFactory.createConnection(properties)
    return OctaviusSessionImpl(conn)
}

/**
 * Establishes a new [OctaviusSession] using the specified [url], [user], and [password].
 *
 * @param url A database url of the form `jdbc:subprotocol:subname`.
 * @param user The database user on whose behalf the connection is being made.
 * @param password The user's password.
 * @return An [OctaviusSession] instance.
 */
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
/**
 * Unwraps this [DataSource] to its underlying [OctaviusDataSource] instance.
 *
 * @return The underlying [OctaviusDataSource].
 */
fun DataSource.unwrapToOctavius(): OctaviusDataSource {
    return this.unwrap(OctaviusDataSource::class.java)
}

/**
 * Unwraps this [DataSource] to an instance of the specified type [T].
 *
 * @return The underlying object of type [T].
 */
inline fun <reified T> DataSource.unwrap(): T {
    return this.unwrap(T::class.java)
}

/**
 * Retrieves a new [OctaviusSession] from this [DataSource].
 *
 * @return An [OctaviusSession] instance.
 * @throws InitializationException if the data source would not hand over a connection - which for a
 * pooled one covers a borrow that timed out and a pool that has been closed. Where the pool was
 * restating a failure of the driver's own, that one is raised instead.
 */
fun DataSource.getOctaviusSession(): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this.getConnection())
}

/**
 * Retrieves a new [OctaviusSession] from this [DataSource] using the specified credentials.
 *
 * Per-call credentials are not something every data source implements: HikariCP refuses them
 * outright, its connections having been opened with the credentials the pool was configured with.
 *
 * @param username The database user on whose behalf the connection is being made.
 * @param password The user's password.
 * @return An [OctaviusSession] instance.
 * @throws InitializationException if the data source would not hand over a connection on these
 * terms. Where it was restating a failure of the driver's own, that one is raised instead.
 */
fun DataSource.getOctaviusSession(username: String, password: String): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this.getConnection(username, password))
}

// Connection
/**
 * Retrieves a new [OctaviusSession] from this [Connection].
 *
 * The connection has to reach an [OctaviusConnection] through `unwrap`, which a pool's proxy answers
 * for as long as it still stands over one - so a connection already handed back is refused here
 * rather than at the first query.
 *
 * @param ownsConnection Whether closing the session should close this connection too. Leave it as it
 * is when the session is what the connection was opened for. Pass `false` when something else owns
 * the connection and will give it back itself - Spring's `DataSourceUtils`, a pool borrowed from by
 * hand - and closing the session then undoes the state it left behind and stops there, rather than
 * returning a connection its owner still believes it holds.
 * @return An [OctaviusSession] instance.
 * @throws InitializationException if a session could not be opened over this connection.
 */
fun Connection.getOctaviusSession(ownsConnection: Boolean = true): OctaviusSession = obtainingSession(this) {
    OctaviusSessionImpl(this, ownsConnection)
}

/**
 * Unwraps this [Connection] to an instance of the specified type [T].
 *
 * @return The underlying object of type [T].
 */
inline fun <reified T : Any> Connection.unwrap(): T {
    return this.unwrap(T::class.java)
}
