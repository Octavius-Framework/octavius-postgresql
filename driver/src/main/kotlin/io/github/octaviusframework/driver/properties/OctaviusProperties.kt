package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.ssl.SslMode
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*

/**
 * The configuration of a connection: typed properties for what the driver reads, and [additionalProperties]
 * for the startup parameters it passes on. Parsed from a JDBC URL by [parse] and rendered back by [toUrl].
 */
class OctaviusProperties {
    /** The user to authenticate as. */
    var user: String? = null

    /** The password to authenticate with. */
    var password: String? = null

    /**
     * The server's host, or several separated by commas - `db1,db2:5433,[::1]:5434` - each with its own port or
     * [portNumber]. An IPv6 address with a port goes in brackets. Defaults to `localhost`.
     */
    var serverName: String? = null

    /** The port of every server in [serverName] that states none. Defaults to `5432`. */
    var portNumber: Int? = null

    /** The database to connect to. Defaults to `postgres`. */
    var databaseName: String? = null

    /** Which kind of server in [serverName] to settle for. Defaults to [TargetSessionAttrs.ANY]. */
    var targetSessionAttrs: TargetSessionAttrs? = null

    /** In what order the servers in [serverName] are tried. Defaults to [LoadBalanceHosts.DISABLE], as listed. */
    var loadBalanceHosts: LoadBalanceHosts? = null

    /**
     * The name the connection reports to the server, shown in `pg_stat_activity`. Defaults to `Octavius Driver`.
     * Wins over an `application_name` in [additionalProperties], which wins over the default.
     */
    var applicationName: String? = null

    /**
     * Seconds to wait for the connect and login, at each address tried. Defaults to
     * `DriverManager.getLoginTimeout()`, or 10 when that is `0`.
     */
    var loginTimeout: Int? = null

    /** Seconds to wait on a socket read before failing. Defaults to `0`, which waits forever. */
    var socketTimeout: Int? = null

    /** Seconds allowed for a cancel request, which travels on a connection of its own. Defaults to 10. */
    var cancelSignalTimeout: Int? = null


    /** Largest row, in bytes, kept in the reusable row buffer. Defaults to `65536`. */
    var maxCachedRowSize: Int? = null

    /** Capacity of the `LISTEN`/`NOTIFY` buffer. Defaults to `256`. */
    var notificationBufferCapacity: Int? = null

    /**
     * The [NoticeHandler] for server notices, shared by every connection opened with these properties. Wins over
     * [noticeHandlerClassName]. Only code can set it: [setProperty] does not reach it and [toUrl] leaves it out.
     */
    var noticeHandler: NoticeHandler? = null

    /**
     * Fully-qualified class name of a [NoticeHandler]. A Kotlin `object` is shared by every connection; a class
     * is instantiated per connection through its no-arg constructor.
     */
    var noticeHandlerClassName: String? = null

    /**
     * Cap, in bytes, of the per-connection parameter buffer, which shrinks back to
     * [initialParameterWriterCapacity] after a query that exceeded it. Defaults to `65536`.
     */
    var maxParameterWriterCapacity: Int? = null

    /** Starting size, in bytes, of the per-connection parameter buffer. Defaults to `1024`. */
    var initialParameterWriterCapacity: Int? = null

    /**
     * Whether a statement logged at `trace` carries its parameter values rather than only their count.
     * Credentials are never logged. Defaults to `false`.
     */
    var logParameterValues: Boolean? = null

    /** Shorthand raising the default SSL mode to [SslMode.REQUIRE]. Ignored when [sslMode] is set. */
    var ssl: Boolean? = null

    /** How much protection the connection demands. Defaults to [SslMode.PREFER]. */
    var sslMode: SslMode? = null

    /** Path to the CA certificate the server is verified against. Defaults to the JVM's trust store. */
    var sslRootCert: String? = null

    /** Path to the client certificate, presented only together with [sslKey]. */
    var sslCert: String? = null

    /** Path to the private key of [sslCert]: PKCS#8 in PEM form, RSA or EC, encrypted or not. */
    var sslKey: String? = null

    /** Decrypts [sslKey] where it is encrypted. An encrypted key without it is refused. */
    var sslPassword: String? = null

    /** How hard to insist that authentication is bound to the TLS channel. Defaults to [ChannelBinding.PREFER]. */
    var channelBinding: ChannelBinding? = null

    /**
     * Everything the driver does not recognise, sent to the server as startup parameters: `search_path`,
     * `statement_timeout` and the rest of PostgreSQL's settings.
     */
    val additionalProperties: MutableMap<String, String> = mutableMapOf()

    /**
     * Sets a property by the name a JDBC URL uses, case-insensitively and with aliases - `host`, `port`,
     * `database`. An unknown name goes to [additionalProperties], so a misspelled one reaches the server.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] does not parse as the type of a known [key].
     */
    fun setProperty(key: String, value: String) {
        when (key.lowercase()) {
            "user" -> user = value
            "password" -> password = value
            "servername", "host" -> serverName = value.also { ServerAddress.parseList(key, it, 5432) }
            "portnumber", "port" -> portNumber = intValue(key, value)
            "databasename", "database" -> databaseName = value
            "targetsessionattrs", "target_session_attrs" -> targetSessionAttrs = TargetSessionAttrs.of(value)
            "loadbalancehosts", "load_balance_hosts" -> loadBalanceHosts = LoadBalanceHosts.of(value)
            "applicationname", "application_name" -> applicationName = value
            "logintimeout" -> loginTimeout = intValue(key, value)
            "sockettimeout" -> socketTimeout = intValue(key, value)
            "cancelsignaltimeout" -> cancelSignalTimeout = intValue(key, value)
            "maxcachedrowsize" -> maxCachedRowSize = intValue(key, value)
            "notificationbuffercapacity" -> notificationBufferCapacity = intValue(key, value)
            "noticehandlerclassname" -> noticeHandlerClassName = value
            "maxparameterwritercapacity" -> maxParameterWriterCapacity = intValue(key, value)
            "initialparameterwritercapacity" -> initialParameterWriterCapacity = intValue(key, value)
            "logparametervalues" -> logParameterValues = booleanValue(key, value)
            "ssl" -> ssl = booleanValue(key, value)
            "sslmode" -> sslMode = SslMode.of(value)
            "sslrootcert" -> sslRootCert = value
            "sslcert" -> sslCert = value
            "sslkey" -> sslKey = value
            "sslpassword" -> sslPassword = value
            "channelbinding", "channel_binding" -> channelBinding = ChannelBinding.of(value)
            else -> additionalProperties[key] = value
        }
    }

    /** Reads [value] as a whole number, or refuses it naming [key] as it was given. */
    private fun intValue(key: String, value: String): Int = value.toIntOrNull()
        ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Property '$key' takes a whole number, but was '$value'."
        )

    /** Reads [value] as exactly `true` or `false`, or refuses it: `yes`, `1` and `on` are refused too. */
    private fun booleanValue(key: String, value: String): Boolean = value.toBooleanStrictOrNull()
        ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Property '$key' takes true or false, but was '$value'."
        )

    /** Copies every property [other] sets over this one; [additionalProperties] are merged key by key. */
    fun merge(other: OctaviusProperties) {
        other.user?.let { user = it }
        other.password?.let { password = it }
        other.serverName?.let { serverName = it }
        other.portNumber?.let { portNumber = it }
        other.databaseName?.let { databaseName = it }
        other.targetSessionAttrs?.let { targetSessionAttrs = it }
        other.loadBalanceHosts?.let { loadBalanceHosts = it }
        other.applicationName?.let { applicationName = it }
        other.loginTimeout?.let { loginTimeout = it }
        other.socketTimeout?.let { socketTimeout = it }
        other.cancelSignalTimeout?.let { cancelSignalTimeout = it }
        other.maxCachedRowSize?.let { maxCachedRowSize = it }
        other.notificationBufferCapacity?.let { notificationBufferCapacity = it }
        other.noticeHandler?.let { noticeHandler = it }
        other.noticeHandlerClassName?.let { noticeHandlerClassName = it }
        other.maxParameterWriterCapacity?.let { maxParameterWriterCapacity = it }
        other.initialParameterWriterCapacity?.let { initialParameterWriterCapacity = it }
        other.logParameterValues?.let { logParameterValues = it }
        other.ssl?.let { ssl = it }
        other.sslMode?.let { sslMode = it }
        other.sslRootCert?.let { sslRootCert = it }
        other.sslCert?.let { sslCert = it }
        other.sslKey?.let { sslKey = it }
        other.sslPassword?.let { sslPassword = it }
        other.channelBinding?.let { channelBinding = it }
        additionalProperties.putAll(other.additionalProperties)
    }

    /**
     * The servers in [serverName], each with its port, or `localhost` alone.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [serverName] does not read as a list of servers.
     */
    internal fun servers(): List<ServerAddress> {
        val defaultPort = portNumber ?: 5432
        val names = serverName ?: return listOf(ServerAddress("localhost", defaultPort))
        return ServerAddress.parseList("serverName", names, defaultPort)
    }

    /** An independent copy of this configuration, passwords included. */
    fun copy(): OctaviusProperties {
        val newProps = OctaviusProperties()
        newProps.merge(this)
        return newProps
    }

    companion object {
        /**
         * Parses `jdbc:octavius://host:port/database?key=value&…`, every part optional, over [info]. Servers are
         * read as [serverName] reads them. What the URL states wins over [info], and a query parameter over the
         * authority; what it leaves out keeps [info]'s value. A URL with another prefix leaves [info] alone. Keys and
         * values are URL-decoded, and a value may hold `=`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if a known property is given a value that does not
         *   parse as its type.
         */
        fun parse(url: String, info: Properties? = null): OctaviusProperties {
            val octaviusProperties = OctaviusProperties()

            info?.forEach { (k, v) ->
                octaviusProperties.setProperty(k.toString(), v.toString())
            }

            val prefix = "jdbc:octavius://"
            if (url.startsWith(prefix)) {
                val withoutPrefix = url.substring(prefix.length)

                // Split off first, so a URL without `/database` does not take the '?' into the host.
                val authorityAndDb = withoutPrefix.substringBefore('?')
                val query = if (withoutPrefix.contains('?')) withoutPrefix.substringAfter('?') else ""

                val slashIndex = authorityAndDb.indexOf('/')
                val hostPort = if (slashIndex != -1) authorityAndDb.substring(0, slashIndex) else authorityAndDb
                val dbNameRaw = if (slashIndex != -1) authorityAndDb.substring(slashIndex + 1) else ""

                // Only what the URL states overrides `info`; the defaults belong to the connection factory.
                if (hostPort.isNotEmpty()) {
                    val servers = URLDecoder.decode(hostPort, "UTF-8")
                    val entries = ServerAddress.entries("host", servers)
                    val single = entries.singleOrNull()
                    if (single != null) {
                        if (single.host.isNotEmpty()) octaviusProperties.serverName = single.host
                        single.port?.let { octaviusProperties.portNumber = it }
                    } else {
                        // Several servers keep their ports beside them, and portNumber stays the port of
                        // those that state none.
                        entries.find { it.host.isEmpty() }?.let { ServerAddress.refuse("host", servers, it.text) }
                        octaviusProperties.serverName = servers
                    }
                }
                if (dbNameRaw.isNotEmpty()) {
                    octaviusProperties.databaseName = URLDecoder.decode(dbNameRaw, "UTF-8")
                }

                // Applied last, so a parameter spelling out `host` or `port` beats the authority.
                if (query.isNotEmpty()) {
                    query.split("&").forEach {
                        // Only the first '=' separates, as `options=-c key=value` needs.
                        val separator = it.indexOf('=')
                        if (separator > 0) {
                            val key = URLDecoder.decode(it.substring(0, separator), "UTF-8")
                            val value = URLDecoder.decode(it.substring(separator + 1), "UTF-8")
                            octaviusProperties.setProperty(key, value)
                        }
                    }
                }
            }
            return octaviusProperties
        }
    }

    /**
     * These properties as a JDBC URL, for logs: [password], [sslPassword] and [noticeHandler] are left out. [copy]
     * is the complete duplicate.
     */
    fun toUrl(): String {
        val db = databaseName ?: "postgres"

        // In the form [parse] reads. A serverName that does not parse is rendered as it stands; refusing it is
        // the connection's business, not the log line's.
        val authority = try {
            servers().joinToString(",")
        } catch (_: InvalidOperationException) {
            "$serverName:${portNumber ?: 5432}"
        }

        val urlBuilder = StringBuilder("jdbc:octavius://$authority/$db")

        val queryParams = mutableMapOf<String, String>()
        user?.let { queryParams["user"] = it }
        targetSessionAttrs?.let { queryParams["target_session_attrs"] = it.value }
        loadBalanceHosts?.let { queryParams["load_balance_hosts"] = it.value }
        loginTimeout?.let { queryParams["loginTimeout"] = it.toString() }
        socketTimeout?.let { queryParams["socketTimeout"] = it.toString() }
        cancelSignalTimeout?.let { queryParams["cancelSignalTimeout"] = it.toString() }
        maxCachedRowSize?.let { queryParams["maxCachedRowSize"] = it.toString() }
        notificationBufferCapacity?.let { queryParams["notificationBufferCapacity"] = it.toString() }
        noticeHandlerClassName?.let { queryParams["noticeHandlerClassName"] = it }
        maxParameterWriterCapacity?.let { queryParams["maxParameterWriterCapacity"] = it.toString() }
        initialParameterWriterCapacity?.let { queryParams["initialParameterWriterCapacity"] = it.toString() }
        logParameterValues?.let { queryParams["logParameterValues"] = it.toString() }
        ssl?.let { queryParams["ssl"] = it.toString() }
        sslMode?.let { queryParams["sslmode"] = it.value }
        sslRootCert?.let { queryParams["sslrootcert"] = it }
        sslCert?.let { queryParams["sslcert"] = it }
        sslKey?.let { queryParams["sslkey"] = it }
        channelBinding?.let { queryParams["channelBinding"] = it.value }

        queryParams.putAll(additionalProperties)

        // After the map, since the typed property is the one that reaches the server.
        applicationName?.let { queryParams["application_name"] = it }

        if (queryParams.isNotEmpty()) {
            urlBuilder.append("?")
            val queryString = queryParams.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            urlBuilder.append(queryString)
        }

        return urlBuilder.toString()
    }
}
