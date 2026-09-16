package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.auth.Authenticator
import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.StartupMessage
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.ssl.SslNegotiator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private val logger = KotlinLogging.logger {}

/** What a connection calls itself when nothing else was asked for. */
private const val DEFAULT_APPLICATION_NAME = "Octavius Driver"

/**
 * Factory object responsible for establishing physical connections to a PostgreSQL database.
 *
 * It handles URL parsing, socket creation, SSL negotiation, and the PostgreSQL startup 
 * and authentication sequences. It ensures that the connected server meets the minimum 
 * version requirement (PostgreSQL 18+).
 */
internal object OctaviusConnectionFactory {
    /**
     * Creates a new database connection using the provided JDBC URL and optional properties.
     *
     * @param url The JDBC URL (e.g., `jdbc:octavius://localhost:5432/db`).
     * @param info Additional connection properties.
     * @return A newly established [Connection] (specifically, an [OctaviusConnection]).
     * @throws InitializationException if the connection cannot be established or the server version is unsupported.
     */
    fun createConnection(url: String, info: Properties? = null): Connection {
        val properties = OctaviusProperties.parse(url, info)
        return createConnection(properties)
    }

    /**
     * Creates a new database connection using the pre-parsed [OctaviusProperties].
     *
     * @param properties The parsed configuration properties.
     * @return A newly established [Connection].
     * @throws InitializationException if the connection cannot be established or the server version is unsupported.
     */
    fun createConnection(properties: OctaviusProperties): Connection {
        val serverName = properties.serverName ?: "localhost"
        val portNumber = properties.portNumber ?: 5432
        val databaseName = properties.databaseName ?: "postgres"

        val user = properties.user ?: "postgres"
        val password = properties.password
        val loginTimeout = properties.loginTimeout ?: DriverManager.getLoginTimeout()
        val notificationBufferCapacity = properties.notificationBufferCapacity ?: 256
        val cancelSignalTimeout = properties.cancelSignalTimeout ?: 10

        val sslConfiguration = SslNegotiator.configurationOf(properties)

        val stream = try {
            val handler = properties.noticeHandler?.let { className ->
                val clazz = try {
                    Thread.currentThread().contextClassLoader.loadClass(className)
                } catch (_: ClassNotFoundException) {
                    Class.forName(className)
                }
                
                val kClass = clazz.kotlin
                kClass.objectInstance as? NoticeHandler
                    ?: clazz.getDeclaredConstructor().newInstance() as NoticeHandler
            }
            PgStream(
                host = serverName,
                port = portNumber,
                loginTimeoutSecs = loginTimeout,
                notificationBufferCapacity = notificationBufferCapacity,
                noticeHandler = handler,
                sslConfiguration = sslConfiguration,
                cancelSignalTimeoutSecs = cancelSignalTimeout
            )
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionReason.CONNECTION_ERROR, e.message, e)
        }

        // Everything past this point runs on an open socket that nothing else holds yet, and none of
        // it is guaranteed to get there: a refused TLS handshake, a client key that will not load, a
        // rejected password, a catalog the login cannot read. Whatever fails has to give the socket
        // back here, or it stays open until the collector reaches it - and a pool retrying a bad
        // password produces one of those per attempt. Dropped rather than closed, because a Terminate
        // is for a session that exists, and a login that failed never opened one; dropping a socket
        // that some path below already closed does nothing.
        try {
            SslNegotiator.negotiate(stream, serverName, portNumber, sslConfiguration)

            // Copied, not used in place: the driver's own startup parameters must not leak back
            // into the caller's properties object, which may be reused for further connections.
            val startupParams = HashMap(properties.additionalProperties)
            startupParams["client_encoding"] = "UTF8"
            startupParams["user"] = user
            startupParams["database"] = databaseName
            // Set after the copy, so the typed property beats an `application_name` put into
            // additionalProperties by hand. Neither given, the driver names itself rather than leave
            // the column blank - and putIfAbsent, so a name set either way is not the one replaced.
            properties.applicationName?.let { startupParams["application_name"] = it }
            startupParams.putIfAbsent("application_name", DEFAULT_APPLICATION_NAME)

            stream.sendMessage(StartupMessage(startupParams))
            stream.flush()

            Authenticator.authenticate(stream, password, properties.channelBinding ?: ChannelBinding.PREFER)

            stream.networkTimeout = properties.socketTimeout?.let { it * 1000 } ?: 0
            properties.maxCachedRowSize?.let { stream.maxCachedRowSize = it }

            val serverVersion = stream.parameters["server_version"]
            if (serverVersion != null) {
                val majorVersion = serverVersion.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (majorVersion < 18) {
                    // Closed rather than left to the catch below: by here there is a session to say
                    // goodbye to, which is the one case in this block where a Terminate means anything.
                    stream.close()
                    throw InitializationException(
                        InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION,
                        "Octavius Driver requires PostgreSQL database version 18 or higher. Received version: $serverVersion"
                    )
                }
            }

            logger.debug {
                "[PID: ${stream.processId}] Connected to $serverName:$portNumber/$databaseName as '$user' " +
                    "(PostgreSQL ${serverVersion ?: "unknown"}, " +
                    "${if (stream.isSecure) "TLS" else "plaintext"}, sslmode=${sslConfiguration.mode.value})"
            }

            return OctaviusConnection(
                stream,
                DatabaseKey(serverName, portNumber, databaseName),
                properties.maxParameterWriterCapacity,
                properties.initialParameterWriterCapacity,
                properties.logParameterValues ?: false
            )
        } catch (e: Throwable) {
            stream.dropSocket()
            throw e
        }
    }
}
