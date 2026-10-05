package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.auth.Authenticator
import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.InitializationException
import io.github.octaviusframework.driver.exception.InitializationExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.io.PgStream
import io.github.octaviusframework.driver.message.frontend.StartupMessage
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.properties.LoadBalanceHosts
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress
import io.github.octaviusframework.driver.properties.TargetSessionAttrs
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.ssl.SslConfiguration
import io.github.octaviusframework.driver.ssl.SslNegotiator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

private val logger = KotlinLogging.logger {}

/** What a connection calls itself when nothing else was asked for. */
private const val DEFAULT_APPLICATION_NAME = "Octavius Driver"

/**
 * What a server answers while it takes no connections - starting up, shutting down, recovering without hot standby.
 * Another server may, so it moves the search on rather than ending it.
 */
private const val CANNOT_CONNECT_NOW = "57P03"

/**
 * Factory object responsible for establishing physical connections to a PostgreSQL database.
 *
 * It handles URL parsing, socket creation, SSL negotiation, and the PostgreSQL startup
 * and authentication sequences. It ensures that the connected server meets the minimum
 * version requirement (PostgreSQL 18+) and reports the `search_path` that requirement is for.
 *
 * Where several servers are listed it also chooses among them, the way `libpq` does - see [createConnection].
 */
internal object OctaviusConnectionFactory {

    /** One server, or one address of it, that did not become the connection: where, and what it failed with. */
    private class Failure(val where: String, val error: Throwable)

    /** How a try at one address ended, where it did not end the whole search. */
    private sealed interface Attempt {
        class Connected(val connection: Connection) : Attempt

        /** Nothing answered at the address, or not in time. The server's next address may. */
        class Unreachable(val failure: Failure) : Attempt

        /** A server answered and is not one to use. Its other addresses lead to the same server. */
        class Unsuitable(val failure: Failure) : Attempt
    }

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
     * Every server listed is tried in turn - in the order given, or shuffled under `load_balance_hosts=random` - and
     * so is every address its name resolves to, until one becomes a connection. What moves the search on follows
     * `libpq`:
     *
     * - nothing answering at an address, or not within `loginTimeout`, moves on to the next address;
     * - a server that takes no connections now (`57P03`), is not the kind `target_session_attrs` asks for, or is not
     *   of the cluster the type catalog belongs to moves on to the next server;
     * - anything else - a rejected password, a failed TLS handshake, a database that does not exist - would be the
     *   same on every server, so it ends the search and is thrown as it is, with what was tried before it suppressed.
     *
     * `prefer-standby` searches twice: for a standby, and where the list has none, for any server.
     *
     * @param properties The parsed configuration properties.
     * @return A newly established [Connection].
     * @throws InitializationException if the connection cannot be established or the server version is unsupported.
     *   Where several addresses were tried and none became a connection, `CONNECTION_ERROR` listing each, unless
     *   one of them ended the search.
     */
    fun createConnection(properties: OctaviusProperties): Connection {
        val servers = properties.servers()
        val databaseKey = DatabaseKey(servers.toSet(), properties.databaseName ?: "postgres")
        val target = properties.targetSessionAttrs ?: TargetSessionAttrs.ANY
        val shuffled = properties.loadBalanceHosts == LoadBalanceHosts.RANDOM

        val sslConfiguration = SslNegotiator.configurationOf(properties)
        val noticeHandler = try {
            noticeHandlerOf(properties.noticeHandler)
        } catch (e: Exception) {
            throw InitializationException(InitializationExceptionReason.CONNECTION_ERROR, e.message, e)
        }

        val passes = if (target == TargetSessionAttrs.PREFER_STANDBY) {
            listOf(TargetSessionAttrs.STANDBY, TargetSessionAttrs.ANY)
        } else {
            listOf(target)
        }
        val order = if (shuffled) servers.shuffled() else servers
        val failures = mutableListOf<Failure>()

        fun record(failure: Failure) {
            failures += failure
            logger.debug { "Could not use ${failure.where}: ${failure.error.summary()}" }
        }

        for (wanted in passes) {
            for (server in order) {
                val addresses = try {
                    InetAddress.getAllByName(server.host).toList().let { if (shuffled) it.shuffled() else it }
                } catch (e: UnknownHostException) {
                    record(Failure("$server", InitializationException(InitializationExceptionReason.CONNECTION_ERROR, e.message, e)))
                    continue
                }
                for (address in addresses) {
                    val attempt = try {
                        attempt(properties, sslConfiguration, noticeHandler, databaseKey, server, address, wanted, target)
                    } catch (e: Exception) {
                        failures.forEach { e.addSuppressed(it.error) }
                        throw e
                    }
                    when (attempt) {
                        is Attempt.Connected -> return attempt.connection
                        is Attempt.Unreachable -> record(attempt.failure)
                        is Attempt.Unsuitable -> {
                            record(attempt.failure)
                            break
                        }
                    }
                }
            }
        }
        throw exhausted(failures, target)
    }

    /**
     * Logs into [server] at [address], and makes a connection of it if it is the kind [wanted] asks for.
     *
     * [target] is what was asked for, for the messages: under `prefer-standby` the first pass wants a standby.
     */
    private fun attempt(
        properties: OctaviusProperties,
        sslConfiguration: SslConfiguration,
        noticeHandler: NoticeHandler?,
        databaseKey: DatabaseKey,
        server: ServerAddress,
        address: InetAddress,
        wanted: TargetSessionAttrs,
        target: TargetSessionAttrs
    ): Attempt {
        val where = "$server (${address.hostAddress})"
        val databaseName = databaseKey.database
        val user = properties.user ?: "postgres"

        val stream = try {
            PgStream(
                host = server.host,
                port = server.port,
                address = address,
                loginTimeoutSecs = properties.loginTimeout ?: DriverManager.getLoginTimeout(),
                notificationBufferCapacity = properties.notificationBufferCapacity ?: 256,
                noticeHandler = noticeHandler,
                sslConfiguration = sslConfiguration,
                cancelSignalTimeoutSecs = properties.cancelSignalTimeout ?: 10
            )
        } catch (e: Exception) {
            return Attempt.Unreachable(Failure(where, InitializationException(InitializationExceptionReason.CONNECTION_ERROR, e.message, e)))
        }

        // Everything past this point runs on an open socket that nothing else holds yet, and none of
        // it is guaranteed to get there: a refused TLS handshake, a client key that will not load, a
        // rejected password, a catalog the login cannot read. Whatever fails has to give the socket
        // back here, or it stays open until the collector reaches it - and a pool retrying a bad
        // password produces one of those per attempt. Dropped rather than closed, because a Terminate
        // is for a session that exists, and a login that failed never opened one; dropping a socket
        // that some path below already closed does nothing.
        try {
            SslNegotiator.negotiate(stream, server.host, server.port, sslConfiguration)

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

            Authenticator.authenticate(stream, properties.password, properties.channelBinding ?: ChannelBinding.PREFER)

            stream.networkTimeout = properties.socketTimeout?.let { it * 1000 } ?: 0
            properties.maxCachedRowSize?.let { stream.maxCachedRowSize = it }

            val serverVersion = stream.parameters["server_version"]
            if (serverVersion != null) {
                val majorVersion = serverVersion.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                if (majorVersion < 18) {
                    stream.close()
                    throw InitializationException(
                        InitializationExceptionReason.UNSUPPORTED_SERVER_VERSION,
                        "Octavius Driver requires PostgreSQL database version 18 or higher. Received version: $serverVersion"
                    )
                }
            }

            // What PostgreSQL 18 is required for. Unqualified type names resolve against the live search path,
            // which 18 reports at login and again whenever it changes. Without it every such name would resolve
            // against a path nobody set, so a login that did not bring it is refused rather than guessed at.
            if (stream.parameters["search_path"] == null) {
                stream.close()
                throw InitializationException(
                    InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER,
                    "The server did not report search_path at login. PostgreSQL 18 reports it on every " +
                        "connection, so something in between is not passing it on - a connection pooler or a " +
                        "proxy, most likely."
                )
            }

            mismatch(stream, wanted, target)?.let { reason ->
                stream.close()
                return Attempt.Unsuitable(
                    Failure(where, InitializationException(
                        InitializationExceptionReason.CONNECTION_ERROR,
                        "$server $reason, and target_session_attrs=${target.value}."
                    ))
                )
            }

            val connection = OctaviusConnection(
                stream,
                databaseKey,
                properties.maxParameterWriterCapacity,
                properties.initialParameterWriterCapacity,
                properties.logParameterValues ?: false
            )

            foreignCluster(connection, databaseKey, server)?.let { reason ->
                // Not used, and not quietly either: a server listed with a database it does not belong to is a
                // configuration to fix, and one that is skipped while another answers would never be noticed.
                logger.warn { "$server $reason" }
                stream.close()
                return Attempt.Unsuitable(
                    Failure(where, InitializationException(InitializationExceptionReason.CONNECTION_ERROR, "$server $reason"))
                )
            }

            GlobalCatalogStore.ensureLoaded(databaseKey, connection.queryExecutor)

            logger.debug {
                "[PID: ${stream.processId}] Connected to $server/$databaseName as '$user' " +
                    "(PostgreSQL ${serverVersion ?: "unknown"}, " +
                    "${if (stream.isSecure) "TLS" else "plaintext"}, sslmode=${sslConfiguration.mode.value})"
            }
            return Attempt.Connected(connection)
        } catch (e: Throwable) {
            stream.dropSocket()
            if (e.timedOut()) return Attempt.Unreachable(Failure(where, e))
            if ((e as? OctaviusException)?.sqlState == CANNOT_CONNECT_NOW) return Attempt.Unsuitable(Failure(where, e))
            throw e
        }
    }

    /**
     * Why the server [stream] logged into is not the kind [wanted] asks for, or `null` when it is.
     *
     * Decided by what the server reported at login, so it costs no query.
     */
    private fun mismatch(stream: PgStream, wanted: TargetSessionAttrs, target: TargetSessionAttrs): String? {
        if (wanted == TargetSessionAttrs.ANY) return null
        val hotStandby = reported(stream, "in_hot_standby", target)
        return when (wanted) {
            TargetSessionAttrs.ANY -> null
            TargetSessionAttrs.PRIMARY -> if (hotStandby) "is in hot standby" else null
            TargetSessionAttrs.STANDBY, TargetSessionAttrs.PREFER_STANDBY -> if (hotStandby) null else "is not in hot standby"
            TargetSessionAttrs.READ_WRITE -> when {
                hotStandby -> "is in hot standby"
                reported(stream, "default_transaction_read_only", target) -> "has default_transaction_read_only on"
                else -> null
            }
            TargetSessionAttrs.READ_ONLY ->
                if (hotStandby || reported(stream, "default_transaction_read_only", target)) null
                else "accepts writes - it is not in hot standby, and default_transaction_read_only is off"
        }
    }

    /**
     * The `on`/`off` parameter [name] as the server reported it at login.
     *
     * PostgreSQL 18 reports both that [mismatch] reads on every connection, so one missing means something in between
     * did not pass it on - and the server's kind is refused rather than guessed at, as a missing `search_path` is.
     */
    private fun reported(stream: PgStream, name: String, target: TargetSessionAttrs): Boolean {
        val value = stream.parameters[name]
        if (value == null) {
            stream.close()
            throw InitializationException(
                InitializationExceptionReason.MISSING_PROTOCOL_PARAMETER,
                "The server did not report $name at login, and target_session_attrs=${target.value} is decided by it. " +
                    "PostgreSQL 18 reports it on every connection, so something in between is not passing it on - " +
                    "a connection pooler or a proxy, most likely."
            )
        }
        return value == "on"
    }

    /**
     * Why the server [connection] reached is not of the cluster the type catalog of [key] belongs to, or `null` when
     * it is.
     *
     * Each server is asked once, and only where [key] lists several: one server cannot be two clusters.
     */
    private fun foreignCluster(connection: OctaviusConnection, key: DatabaseKey, server: ServerAddress): String? {
        if (key.servers.size < 2) return null
        val holder = connection.catalogHolder
        if (holder.isAdmitted(server)) return null

        val types = TypeManager(holder)
        val identifier = connection.queryExecutor.query(
            "SELECT system_identifier FROM pg_catalog.pg_control_system()",
            mapper = ResultMapper(types.catalog, null, types.lookup)
        ).single().getRaw(0) as Long

        val cluster = holder.admit(server, identifier)
        if (cluster == identifier) return null
        return "belongs to the cluster with system identifier $identifier, and the type catalog of $key was read " +
            "from the one with $cluster. The catalog maps types by OID, which only a physical standby is sure to " +
            "share with its primary, so this server is not used."
    }

    /**
     * What to throw once every address has been tried and none became a connection.
     *
     * A single failure is thrown as it is, so one server at one address fails exactly as it would with nothing to
     * choose between. Several go under one exception that lists them, each also suppressed on it.
     */
    private fun exhausted(failures: List<Failure>, target: TargetSessionAttrs): Throwable {
        failures.singleOrNull()?.let { return it.error }
        val under = if (target == TargetSessionAttrs.ANY) "" else " under target_session_attrs=${target.value}"
        val exception = InitializationException(
            InitializationExceptionReason.CONNECTION_ERROR,
            "No server could be used$under. Tried:\n" +
                failures.joinToString("\n") { "  ${it.where}: ${it.error.summary()}" }
        )
        failures.forEach { exception.addSuppressed(it.error) }
        return exception
    }

    /** The configured [NoticeHandler]: a Kotlin `object` as it is, a class instantiated through its no-arg constructor. */
    private fun noticeHandlerOf(className: String?): NoticeHandler? {
        if (className == null) return null
        val clazz = try {
            Thread.currentThread().contextClassLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            Class.forName(className)
        }
        return clazz.kotlin.objectInstance as? NoticeHandler
            ?: clazz.getDeclaredConstructor().newInstance() as NoticeHandler
    }

    /** Whether this is, or was caused by, a socket wait running out - `loginTimeout` spent at one address. */
    private fun Throwable.timedOut(): Boolean = generateSequence(this) { it.cause }.any { it is SocketTimeoutException }

    /** The part of this a person reads, for a line in a list - an [OctaviusException]'s `message` is its identifier. */
    private fun Throwable.summary(): String = when (this) {
        is InitializationException -> details
        is OctaviusException -> serverErrorMessage?.message ?: cause?.message
        else -> null
    } ?: message ?: javaClass.name
}
