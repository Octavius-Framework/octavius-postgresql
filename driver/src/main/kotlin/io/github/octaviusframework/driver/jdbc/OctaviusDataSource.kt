package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.auth.ChannelBinding
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.wrapSqlException
import io.github.octaviusframework.driver.notice.NoticeHandler
import io.github.octaviusframework.driver.properties.LoadBalanceHosts
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.TargetSessionAttrs
import io.github.octaviusframework.driver.ssl.SslMode
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A JDBC [DataSource] opening a new connection on every call. It has a bean property for every property of
 * [OctaviusProperties]; startup parameters go through [url] or [setProperty].
 */
class OctaviusDataSource : DataSource {
    private val octaviusProperties = OctaviusProperties()

    /** Set, parses the URL over the current configuration; read, renders it without the passwords. */
    var url: String
        get() = octaviusProperties.toUrl()
        set(value) {
            val parsed = OctaviusProperties.parse(value)
            octaviusProperties.merge(parsed)
        }

    /** The server's host, or several separated by commas: [OctaviusProperties.serverName]. */
    var serverName: String?
        get() = octaviusProperties.serverName
        set(value) { octaviusProperties.serverName = value }

    /** The port of every server that states none. Defaults to 5432. */
    var portNumber: Int
        get() = octaviusProperties.portNumber ?: 5432
        set(value) { octaviusProperties.portNumber = value }

    /** The database to connect to. */
    var databaseName: String?
        get() = octaviusProperties.databaseName
        set(value) { octaviusProperties.databaseName = value }

    /** Which kind of server in [serverName] to settle for. Defaults to [TargetSessionAttrs.ANY]. */
    var targetSessionAttrs: TargetSessionAttrs?
        get() = octaviusProperties.targetSessionAttrs
        set(value) { octaviusProperties.targetSessionAttrs = value }

    /** In what order the servers in [serverName] are tried. Defaults to [LoadBalanceHosts.DISABLE], as listed. */
    var loadBalanceHosts: LoadBalanceHosts?
        get() = octaviusProperties.loadBalanceHosts
        set(value) { octaviusProperties.loadBalanceHosts = value }

    /** The name a connection reports to the server, shown in `pg_stat_activity`. Defaults to `Octavius Driver`. */
    var applicationName: String?
        get() = octaviusProperties.applicationName
        set(value) { octaviusProperties.applicationName = value }

    /** The user to authenticate as. */
    var user: String?
        get() = octaviusProperties.user
        set(value) { octaviusProperties.user = value }

    /** The password to authenticate with. */
    var password: String?
        get() = octaviusProperties.password
        set(value) { octaviusProperties.password = value }

    /** Shorthand raising the default SSL mode to `REQUIRE`. Ignored when [sslMode] is set. */
    var ssl: Boolean?
        get() = octaviusProperties.ssl
        set(value) { octaviusProperties.ssl = value }

    /** How much protection the connection demands. Defaults to [SslMode.PREFER]. */
    var sslMode: SslMode?
        get() = octaviusProperties.sslMode
        set(value) { octaviusProperties.sslMode = value }

    /** Path to the CA certificate the server is verified against. Defaults to the JVM's trust store. */
    var sslRootCert: String?
        get() = octaviusProperties.sslRootCert
        set(value) { octaviusProperties.sslRootCert = value }

    /** Path to the client certificate, presented only together with [sslKey]. */
    var sslCert: String?
        get() = octaviusProperties.sslCert
        set(value) { octaviusProperties.sslCert = value }

    /** Path to the private key of [sslCert]: PKCS#8 in PEM form, RSA or EC, encrypted or not. */
    var sslKey: String?
        get() = octaviusProperties.sslKey
        set(value) { octaviusProperties.sslKey = value }

    /** Decrypts [sslKey] where it is encrypted. An encrypted key without it is refused. */
    var sslPassword: String?
        get() = octaviusProperties.sslPassword
        set(value) { octaviusProperties.sslPassword = value }

    /** How hard to insist that authentication is bound to the TLS channel. Defaults to [ChannelBinding.PREFER]. */
    var channelBinding: ChannelBinding?
        get() = octaviusProperties.channelBinding
        set(value) { octaviusProperties.channelBinding = value }

    /** Seconds to wait on a socket read before failing. `0` waits forever. */
    var socketTimeout: Int
        get() = octaviusProperties.socketTimeout ?: 0
        set(value) { octaviusProperties.socketTimeout = value }

    /** Seconds allowed for a cancel request, its connect and its reads together. */
    var cancelSignalTimeout: Int
        get() = octaviusProperties.cancelSignalTimeout ?: 10
        set(value) { octaviusProperties.cancelSignalTimeout = value }

    /** Largest row, in bytes, kept in the reusable row buffer. */
    var maxCachedRowSize: Int
        get() = octaviusProperties.maxCachedRowSize ?: 65536
        set(value) { octaviusProperties.maxCachedRowSize = value }

    /** Capacity of the `LISTEN`/`NOTIFY` buffer. */
    var notificationBufferCapacity: Int
        get() = octaviusProperties.notificationBufferCapacity ?: 256
        set(value) { octaviusProperties.notificationBufferCapacity = value }

    /** Fully-qualified class name of a [NoticeHandler], used where [noticeHandler] is not set. */
    var noticeHandlerClassName: String?
        get() = octaviusProperties.noticeHandlerClassName
        set(value) { octaviusProperties.noticeHandlerClassName = value }

    /** The [NoticeHandler] for server notices, shared by every connection this data source opens. */
    var noticeHandler: NoticeHandler?
        get() = octaviusProperties.noticeHandler
        set(value) { octaviusProperties.noticeHandler = value }

    /** Starting size, in bytes, of the per-connection parameter buffer. */
    var initialParameterWriterCapacity: Int
        get() = octaviusProperties.initialParameterWriterCapacity ?: 1024
        set(value) { octaviusProperties.initialParameterWriterCapacity = value }

    /** Cap of the parameter buffer, which shrinks back to [initialParameterWriterCapacity] after exceeding it. */
    var maxParameterWriterCapacity: Int
        get() = octaviusProperties.maxParameterWriterCapacity ?: 65536
        set(value) { octaviusProperties.maxParameterWriterCapacity = value }

    /** Whether a statement logged at `trace` carries its parameter values rather than only their count. */
    var logParameterValues: Boolean
        get() = octaviusProperties.logParameterValues ?: false
        set(value) { octaviusProperties.logParameterValues = value }

    /**
     * Sets a property by the name a JDBC URL uses, as [OctaviusProperties.setProperty] does: an unknown name, such
     * as `search_path`, is sent to the server as a startup parameter.
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
    override fun <T> unwrap(iface: Class<T>): T = wrapSqlException {
        if (iface.isInstance(this)) return@wrapSqlException this as T
        throw InvalidOperationException(InvalidOperationExceptionReason.UNWRAP_ERROR, "Cannot unwrap to ${iface.name}")
    }

    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

