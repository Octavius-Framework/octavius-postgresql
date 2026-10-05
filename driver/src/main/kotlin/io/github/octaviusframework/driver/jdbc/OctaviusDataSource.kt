package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.properties.LoadBalanceHosts
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.TargetSessionAttrs
import io.github.octaviusframework.driver.ssl.SslMode
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Implementation of the standard JDBC [DataSource] interface for the Octavius driver.
 *
 * This class serves as a factory for obtaining connections to a PostgreSQL database.
 * It encapsulates the configuration properties required to establish a connection,
 * such as the server URL, credentials, and SSL settings.
 *
 * Configuration properties can be set either via the [url] property or individually
 * using the respective accessor properties. Every field of
 * [OctaviusProperties][io.github.octaviusframework.driver.properties.OctaviusProperties] has an
 * accessor here, so a pool that configures a `DataSource` through JavaBean setters - HikariCP's
 * `addDataSourceProperty`, for one - can reach all of them; anything else goes through
 * [setProperty], including server startup parameters.
 */
class OctaviusDataSource : DataSource {
    private val octaviusProperties = OctaviusProperties()

    /**
     * The JDBC URL used to connect to the database.
     * Setting this property will parse the URL and merge the properties.
     *
     * Reading it back renders the current configuration **without either secret** - neither the
     * password nor `sslpassword`, see [OctaviusProperties.toUrl]. Both remain available through
     * [password] and [sslPassword] themselves.
     */
    var url: String
        get() = octaviusProperties.toUrl()
        set(value) {
            val parsed = OctaviusProperties.parse(value)
            octaviusProperties.merge(parsed)
        }

    /**
     * The hostname or IP address of the PostgreSQL server, or several separated by commas, each with a port of
     * its own or [portNumber]'s - see [OctaviusProperties.serverName].
     */
    var serverName: String?
        get() = octaviusProperties.serverName
        set(value) { octaviusProperties.serverName = value }

    /**
     * The port number on which the PostgreSQL server is listening - with several servers, the port of every one
     * not stating its own. Defaults to 5432.
     */
    var portNumber: Int
        get() = octaviusProperties.portNumber ?: 5432
        set(value) { octaviusProperties.portNumber = value }

    /**
     * The name of the database to connect to.
     */
    var databaseName: String?
        get() = octaviusProperties.databaseName
        set(value) { octaviusProperties.databaseName = value }

    /**
     * Which kind of server to settle for among those in [serverName]. Defaults to [TargetSessionAttrs.ANY].
     */
    var targetSessionAttrs: TargetSessionAttrs?
        get() = octaviusProperties.targetSessionAttrs
        set(value) { octaviusProperties.targetSessionAttrs = value }

    /**
     * In what order the servers in [serverName] are tried. Defaults to [LoadBalanceHosts.DISABLE], as listed.
     */
    var loadBalanceHosts: LoadBalanceHosts?
        get() = octaviusProperties.loadBalanceHosts
        set(value) { octaviusProperties.loadBalanceHosts = value }

    /**
     * The name this connection reports to the server, visible in `pg_stat_activity`. Unset, the
     * driver's own name - `Octavius Driver` - is sent instead.
     */
    var applicationName: String?
        get() = octaviusProperties.applicationName
        set(value) { octaviusProperties.applicationName = value }

    /**
     * The username for authenticating with the database.
     */
    var user: String?
        get() = octaviusProperties.user
        set(value) { octaviusProperties.user = value }

    /**
     * The password for authenticating with the database.
     */
    var password: String?
        get() = octaviusProperties.password
        set(value) { octaviusProperties.password = value }

    /**
     * Shorthand raising the default SSL mode to `REQUIRE`. Ignored when [sslMode] is set explicitly.
     */
    var ssl: Boolean?
        get() = octaviusProperties.ssl
        set(value) { octaviusProperties.ssl = value }

    /**
     * The SSL mode to use (e.g., disable, require, verify-ca, verify-full).
     */
    var sslMode: SslMode?
        get() = octaviusProperties.sslMode
        set(value) { octaviusProperties.sslMode = value }

    /**
     * The path to the root certificate file for verifying the server's certificate. Optional: left
     * unset, verification under `verify-ca` and above uses the JVM's default trust store.
     */
    var sslRootCert: String?
        get() = octaviusProperties.sslRootCert
        set(value) { octaviusProperties.sslRootCert = value }

    /**
     * The path to the client certificate file for SSL authentication. Takes effect only together with
     * [sslKey]; with either missing, no client certificate is presented.
     */
    var sslCert: String?
        get() = octaviusProperties.sslCert
        set(value) { octaviusProperties.sslCert = value }

    /**
     * The path to the client private key file for SSL authentication. PKCS#8 in PEM form, encrypted
     * or not - [sslPassword] opens an encrypted one - and RSA or EC alike, the algorithm being read
     * off [sslCert] rather than assumed.
     */
    var sslKey: String?
        get() = octaviusProperties.sslKey
        set(value) { octaviusProperties.sslKey = value }

    /**
     * Decrypts [sslKey] where that key is encrypted - a PEM file opening on `BEGIN ENCRYPTED PRIVATE
     * KEY`. An unencrypted key ignores it; an encrypted one without it is refused, naming this property.
     */
    var sslPassword: String?
        get() = octaviusProperties.sslPassword
        set(value) { octaviusProperties.sslPassword = value }

    /**
     * How hard to insist that authentication be bound to the TLS channel. Defaults to
     * [ChannelBinding.PREFER].
     */
    var channelBinding: ChannelBinding?
        get() = octaviusProperties.channelBinding
        set(value) { octaviusProperties.channelBinding = value }

    /**
     * Seconds to wait on a socket read before failing. `0` waits forever.
     */
    var socketTimeout: Int
        get() = octaviusProperties.socketTimeout ?: 0
        set(value) { octaviusProperties.socketTimeout = value }

    /**
     * Seconds allowed for a cancel request, covering both its connect and its reads.
     */
    var cancelSignalTimeout: Int
        get() = octaviusProperties.cancelSignalTimeout ?: 10
        set(value) { octaviusProperties.cancelSignalTimeout = value }

    /**
     * Largest row, in bytes, kept in the reusable row buffer.
     */
    var maxCachedRowSize: Int
        get() = octaviusProperties.maxCachedRowSize ?: 65536
        set(value) { octaviusProperties.maxCachedRowSize = value }

    /**
     * Capacity of the `LISTEN`/`NOTIFY` buffer.
     */
    var notificationBufferCapacity: Int
        get() = octaviusProperties.notificationBufferCapacity ?: 256
        set(value) { octaviusProperties.notificationBufferCapacity = value }

    /**
     * Fully-qualified class name of a `NoticeHandler` for server notices.
     */
    var noticeHandler: String?
        get() = octaviusProperties.noticeHandler
        set(value) { octaviusProperties.noticeHandler = value }

    /**
     * Starting size, in bytes, of the per-connection parameter buffer.
     */
    var initialParameterWriterCapacity: Int
        get() = octaviusProperties.initialParameterWriterCapacity ?: 1024
        set(value) { octaviusProperties.initialParameterWriterCapacity = value }

    /**
     * Cap for the per-connection parameter buffer; it shrinks back to
     * [initialParameterWriterCapacity] after a query that exceeded it.
     */
    var maxParameterWriterCapacity: Int
        get() = octaviusProperties.maxParameterWriterCapacity ?: 65536
        set(value) { octaviusProperties.maxParameterWriterCapacity = value }

    /**
     * Whether a traced statement carries the values bound to it, rather than only how many there
     * were. Off unless set, and effective only at `trace`.
     */
    var logParameterValues: Boolean
        get() = octaviusProperties.logParameterValues ?: false
        set(value) { octaviusProperties.logParameterValues = value }

    /**
     * Sets any property this class does not expose directly, by the same name a JDBC URL would
     * use. Anything the driver does not recognise is sent to the server as a startup parameter,
     * which is what makes this the programmatic route to `search_path`, `statement_timeout` and
     * friends - `application_name` has [applicationName] and needs no detour.
     *
     * A name the driver *does* recognise is parsed as its type here and now, and refused if it will
     * not parse - see [OctaviusProperties.setProperty].
     */
    fun setProperty(name: String, value: String) {
        octaviusProperties.setProperty(name, value)
    }

    private var logWriter: PrintWriter? = null

    override fun getConnection(): Connection {
        return getConnection(user, password)
    }

    override fun getConnection(username: String?, pass: String?): Connection {

        val props = octaviusProperties.copy()
        
        if (username != null) props.user = username
        if (pass != null) props.password = pass
        
        return OctaviusConnectionFactory.createConnection(props)
    }

    override fun getLogWriter(): PrintWriter? = logWriter
    override fun setLogWriter(out: PrintWriter?) {
        logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) { // required by Hikari
        octaviusProperties.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = octaviusProperties.loginTimeout ?: 0

    override fun getParentLogger(): Logger = throw InvalidOperationException(InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED)

    @Suppress("UNCHECKED_CAST")
    override fun <T> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) {
            return this as T
        }
        throw InvalidOperationException(InvalidOperationExceptionReason.UNWRAP_ERROR, "Cannot unwrap to ${iface.name}")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

