package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.util.*
import java.util.logging.Logger

/**
 * The main entry point for the JDBC API into the Octavius driver.
 *
 * This class implements the standard `java.sql.Driver` interface and is automatically
 * registered with the [DriverManager] when the class is loaded. It accepts URLs starting
 * with `jdbc:octavius:`.
 */
internal class OctaviusDriver : Driver {
    companion object {
        init {
            DriverManager.registerDriver(OctaviusDriver())
        }
    }

    override fun connect(url: String, info: Properties?): Connection? {
        if (!acceptsURL(url)) return null
        
        return OctaviusConnectionFactory.createConnection(url, info)
    }

    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:octavius:")
    }

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> {
        return emptyArray()
    }

    override fun getMajorVersion(): Int = 2
    override fun getMinorVersion(): Int = 1
    override fun jdbcCompliant(): Boolean = false
    override fun getParentLogger(): Logger = throw InvalidOperationException(InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED)
}

