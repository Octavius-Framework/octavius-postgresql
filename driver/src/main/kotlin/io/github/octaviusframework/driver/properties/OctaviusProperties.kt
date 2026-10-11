package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.ssl.SslMode
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*

/**
 * Holds all configuration properties for establishing a connection to a PostgreSQL database
 * using the Octavius driver.
 *
 * It provides a strongly-typed representation of common connection properties (e.g., host,
 * port, user, password, ssl settings) and stores any unrecognised properties in a map.
 * This class also provides utility methods for parsing properties from a JDBC URL and 
 * converting the properties back into a valid JDBC URL.
 */
class OctaviusProperties {
    /** The user to authenticate as. */
    var user: String? = null

    /** The password to authenticate with. */
    var password: String? = null

    /**
     * Hostname or IP address of the server, or several separated by commas - `db1,db2:5433,[::1]:5434` - each
     * with a port of its own or [portNumber]'s. Defaults to `localhost`.
     *
     * Several are tried in turn until one becomes a connection; [targetSessionAttrs] says which kind of server
     * that may be, and [loadBalanceHosts] in what order they are tried. An IPv6 address with a port of its own
     * goes in brackets; a lone one without may go bare.
     */
    var serverName: String? = null

    /** Port the server listens on - with several in [serverName], of every one not stating its own. Defaults to `5432`. */
    var portNumber: Int? = null

    /** The database to connect to. Defaults to `postgres`. */
    var databaseName: String? = null

    /**
     * Which kind of server to settle for among those in [serverName]: a primary, a standby, one accepting writes,
     * and so on. Defaults to [TargetSessionAttrs.ANY], the first that accepts the connection.
     */
    var targetSessionAttrs: TargetSessionAttrs? = null

    /** In what order the servers in [serverName] are tried. Defaults to [LoadBalanceHosts.DISABLE], as listed. */
    var loadBalanceHosts: LoadBalanceHosts? = null

    /**
     * The name this connection reports to the server, where it shows up in `pg_stat_activity` and in
     * any log line written with `%a`. Left unset, the driver names itself - `Octavius Driver` - so
     * a connection is never one of a row of blanks.
     *
     * It is a startup parameter like the ones in [additionalProperties], and it wins over an
     * `application_name` put there by hand. That one still beats the driver's own name.
     */
    var applicationName: String? = null

    /**
     * Seconds to wait for the socket connect and login. Falls back to `DriverManager.getLoginTimeout()`,
     * or 10 seconds when that is `0`.
     *
     * It is spent per address: with several servers, or a name resolving to several addresses, each one tried
     * gets the whole of it.
     */
    var loginTimeout: Int? = null

    /** Seconds to wait on a socket read before failing. Defaults to `0`, which waits forever. */
    var socketTimeout: Int? = null

    /**
     * Seconds allowed for a cancel request, covering both its connect and its reads.
     *
     * A cancel travels on a connection of its own, so it can get stuck on a server that the
     * session's own connection is not stuck on - which is why it has a budget separate from
     * [loginTimeout] and [socketTimeout]. Defaults to 10 seconds.
     */
    var cancelSignalTimeout: Int? = null


    /** Largest row, in bytes, kept in the reusable row buffer. Defaults to `65536`. */
    var maxCachedRowSize: Int? = null

    /** Capacity of the `LISTEN`/`NOTIFY` buffer. Defaults to `256`. */
    var notificationBufferCapacity: Int? = null

    /**
     * Fully-qualified class name of a [NoticeHandler][io.github.octaviusframework.driver.notice.NoticeHandler]
     * for server notices. A Kotlin `object` is reused as a singleton across connections; a class is
     * instantiated per connection through its no-arg constructor.
     */
    var noticeHandler: String? = null

    /**
     * Cap, in bytes, for the per-connection parameter buffer; it shrinks back to
     * [initialParameterWriterCapacity] after a query that exceeded it. Defaults to `65536`.
     */
    var maxParameterWriterCapacity: Int? = null

    /** Starting size, in bytes, of the per-connection parameter buffer. Defaults to `1024`. */
    var initialParameterWriterCapacity: Int? = null

    /**
     * Whether a traced statement carries the values bound to it, rather than only how many there
     * were. Defaults to `false`.
     *
     * Takes effect only at `trace`, and never applies to passwords or authentication material,
     * which no level ever writes. Values are truncated the way an exception's are.
     */
    var logParameterValues: Boolean? = null

    /** Shorthand raising the default SSL mode to [SslMode.REQUIRE]. Ignored when [sslMode] is set. */
    var ssl: Boolean? = null

    /** How much protection the connection demands. Defaults to [SslMode.PREFER]. */
    var sslMode: SslMode? = null

    /**
     * Path to the CA certificate the server's chain is verified against, for `verify-ca` and above.
     * Optional: left unset, the JVM's default trust store is used.
     */
    var sslRootCert: String? = null

    /**
     * Path to the client certificate, for certificate authentication. Takes effect only together with
     * [sslKey]; with either missing, no client certificate is presented.
     */
    var sslCert: String? = null

    /**
     * Path to the client private key that goes with [sslCert]. PKCS#8 in PEM form, encrypted or not -
     * an encrypted one is opened with [sslPassword]; its algorithm is read off [sslCert], so RSA and
     * EC alike work without saying which.
     */
    var sslKey: String? = null

    /**
     * Decrypts [sslKey] where that key is encrypted - a PEM file opening on `BEGIN ENCRYPTED PRIVATE
     * KEY`. An unencrypted key ignores it; an encrypted one without it is refused, naming this property.
     */
    var sslPassword: String? = null

    /**
     * How hard to insist that authentication be bound to the TLS channel. Defaults to
     * [ChannelBinding.PREFER]: bind when the connection is encrypted and the server offers it.
     */
    var channelBinding: ChannelBinding? = null

    /**
     * Everything the driver does not recognise, sent to the server as startup parameters. This is what
     * carries `search_path`, `statement_timeout` and the rest of PostgreSQL's own settings; the one
     * with a property of its own is [applicationName].
     */
    val additionalProperties: MutableMap<String, String> = mutableMapOf()

    /**
     * Sets one property by name, using the same names a JDBC URL would.
     *
     * Names are matched case-insensitively, and several have aliases - `host` for `serverName`, `port`
     * for `portNumber`, `database` for `databaseName`. A name that matches nothing goes to
     * [additionalProperties] and reaches the server as a startup parameter, so a typo in a known name
     * is not rejected here; it becomes a startup parameter and the server complains instead.
     *
     * A value that will not parse as the property's type is refused, naming the property and the
     * value. Nothing is guessed and nothing quietly falls back to a default: a misspelled `sslmode`
     * would otherwise verify nothing, and a `socketTimeout` written `30s` would wait forever - both
     * silently, and both a long way from the line that caused them.
     *
     * @param key The property name.
     * @param value The value, as a string.
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] does not parse as the type of a
     *   recognized [key].
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
            "noticehandler" -> noticeHandler = value
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

    /**
     * Reads [value] as the whole number [key] is declared to hold, or refuses it.
     *
     * [key] is quoted as it was given rather than as the property is spelled here, so a message
     * about `port` does not answer with `portNumber`.
     */
    private fun intValue(key: String, value: String): Int = value.toIntOrNull()
        ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Property '$key' takes a whole number, but was '$value'."
        )

    /**
     * Reads [value] as the `true` or `false` [key] is declared to hold, or refuses it.
     *
     * Strictly: `yes`, `1` and `on` are refused rather than read as `true`, and - what actually
     * bites - rather than read as `false` the way a lenient parse would have them.
     */
    private fun booleanValue(key: String, value: String): Boolean = value.toBooleanStrictOrNull()
        ?: throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Property '$key' takes true or false, but was '$value'."
        )

    /**
     * Copies every set property of [other] over this one.
     *
     * A property left `null` in [other] is not copied, so it does not erase what is already here;
     * [additionalProperties] are merged key by key, with [other]'s winning on a collision.
     *
     * @param other The properties to overlay.
     */
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
     * The servers a connection is tried against, in the order [serverName] lists them, each with its own
     * port or [portNumber] - and `localhost` alone when no server is named.
     *
     * @throws InvalidOperationException `INVALID_ARGUMENT` if [serverName] does not read as a list of servers.
     */
    internal fun servers(): List<ServerAddress> {
        val defaultPort = portNumber ?: 5432
        val names = serverName ?: return listOf(ServerAddress("localhost", defaultPort))
        return ServerAddress.parseList("serverName", names, defaultPort)
    }

    /**
     * Returns a complete, independent duplicate of this configuration, password included.
     *
     * This is the lossless counterpart to [toUrl], which deliberately omits the password.
     *
     * @return A new instance holding the same values.
     */
    fun copy(): OctaviusProperties {
        val newProps = OctaviusProperties()
        newProps.merge(this)
        return newProps
    }

    companion object {
        /**
         * Parses a JDBC URL, optionally overlaid on a [Properties] set.
         *
         * The expected form is `jdbc:octavius://host:port/database?key=value&…`, with the host, port,
         * database and query string all optional. A URL that does not start with that prefix is not an
         * error: nothing is parsed out of it and only [info] takes effect.
         *
         * The address is read the way [serverName] reads one - see [ServerAddress.entries] - so an IPv6
         * address with a port goes in brackets, `jdbc:octavius://[::1]:5432/res_publica`, and a single one
         * is stored without them: they separate the address from the port, and are not part of the host a
         * certificate is matched against.
         *
         * Several servers are separated by commas, each with a port of its own or none -
         * `jdbc:octavius://db1:5432,db2,[::1]:5433/res_publica`. A single server's port goes into
         * [portNumber]; several keep theirs beside them in [serverName], where [portNumber] is the port of
         * those stating none.
         *
         * **Later wins, and silence is not a value.** [info] is applied first and the URL over it, so
         * the URL overrides it - but only where the URL actually states something. A URL that omits the
         * host, the port or the database leaves whatever [info] supplied for it in place rather than
         * replacing it with a default. Within the URL the same rule applies left to right, so a
         * query-string `host` or `port` parameter beats the authority that precedes it.
         *
         * Nothing here fills in defaults for an unstated host, port or database: they stay `null` and
         * are resolved to `localhost`, `5432` and `postgres` when the connection is opened.
         *
         * Keys and values are URL-decoded, and only the first `=` in a parameter separates them, so a
         * value containing more of them - a password, an `options=-c search_path=curia` string - arrives
         * intact.
         *
         * Values are parsed here rather than at connect time, so a URL carrying one that will not
         * parse is refused by this call - see [setProperty], which every parameter goes through.
         *
         * @param url The JDBC URL.
         * @param info Additional properties, applied beneath the URL.
         * @return The parsed properties.
         * @throws InvalidOperationException `INVALID_ARGUMENT` if a recognized property is given a
         *   value that does not parse as its type.
         */
        fun parse(url: String, info: Properties? = null): OctaviusProperties {
            val octaviusProperties = OctaviusProperties()

            info?.forEach { (k, v) ->
                octaviusProperties.setProperty(k.toString(), v.toString())
            }

            val prefix = "jdbc:octavius://"
            if (url.startsWith(prefix)) {
                val withoutPrefix = url.substring(prefix.length)

                // The query is split off before the authority, not after the database name: a URL that
                // omits the `/database` part would otherwise swallow the '?' into the host, taking every
                // parameter with it.
                val authorityAndDb = withoutPrefix.substringBefore('?')
                val query = if (withoutPrefix.contains('?')) withoutPrefix.substringAfter('?') else ""

                val slashIndex = authorityAndDb.indexOf('/')
                val hostPort = if (slashIndex != -1) authorityAndDb.substring(0, slashIndex) else authorityAndDb
                val dbNameRaw = if (slashIndex != -1) authorityAndDb.substring(slashIndex + 1) else ""

                // The URL is applied over `info`, so it wins - but only where it actually states
                // something. An absent host, port or database leaves whatever `info` supplied in place
                // instead of overwriting it with a default; the defaults belong to the connection
                // factory, which is the one place that knows them.
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
                        // Only the first '=' separates: a value is free to contain more of them,
                        // as a password or an `options=-c key=value` string routinely does.
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
     * Renders these properties as a JDBC URL.
     *
     * Neither secret is rendered: not [password], and not [sslPassword], which unlocks the client
     * private key. The result is a string that tends to end up in logs and diagnostics, and nothing
     * in the driver reconstructs a connection from it - so a URL is not a lossless copy and cannot
     * be used as one. Use [copy] when you need a complete duplicate of the configuration.
     */
    fun toUrl(): String {
        val db = databaseName ?: "postgres"

        // Every server with its port, and an IPv6 one bracketed - the form [parse] reads them out of, and
        // the only one where the colon before the port is unambiguous. A serverName that does not read as
        // a list is rendered as it stands: this is a string for a log line, and refusing it is the
        // connection's business, not the log's.
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
        noticeHandler?.let { queryParams["noticeHandler"] = it }
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

        // Rendered under PostgreSQL's own spelling, and after the map, for the same reason the startup
        // message sets it last: the typed property is the one that reaches the server, so an
        // `application_name` left in additionalProperties must not be what the URL shows.
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
