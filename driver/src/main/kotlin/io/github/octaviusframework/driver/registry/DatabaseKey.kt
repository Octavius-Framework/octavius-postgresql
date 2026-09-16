package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.OctaviusProperties

/**
 * Identifies the physical database (host, port, database name) a [CatalogHolder] belongs to.
 *
 * Deliberately excludes the rest of the connection URL (credentials, SSL settings, timeouts, ...):
 * none of that affects the type catalog, so keying on it would fragment the cache and would
 * needlessly keep a password alive as a map key for the lifetime of the JVM.
 */
internal data class DatabaseKey(val host: String, val port: Int, val database: String) {
    companion object {
        fun from(properties: OctaviusProperties): DatabaseKey = DatabaseKey(
            properties.serverName ?: "localhost",
            properties.portNumber ?: 5432,
            properties.databaseName ?: "postgres"
        )
    }
}
