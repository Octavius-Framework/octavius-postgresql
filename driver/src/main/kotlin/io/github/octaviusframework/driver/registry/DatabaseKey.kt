package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress

/**
 * Identifies the database a [CatalogHolder] belongs to: the servers it is reached on, and its name.
 *
 * The servers are a set, so the order a URL happens to list them in does not make a second catalog of the same
 * database. Listing several in one URL declares them one database, and a catalog shared between them is correct
 * only while they are one cluster - which is checked as each is first connected to, see [CatalogHolder.admit].
 *
 * Deliberately excludes the rest of the connection URL (credentials, SSL settings, timeouts, ...):
 * none of that affects the type catalog, so keying on it would fragment the cache and would
 * needlessly keep a password alive as a map key for the lifetime of the JVM.
 */
internal data class DatabaseKey(val servers: Set<ServerAddress>, val database: String) {

    /** `host:port/database`, or `host:port,host:port/database` for several - what a log line names it by. */
    override fun toString(): String = "${servers.sorted().joinToString(",")}/$database"

    companion object {
        fun from(properties: OctaviusProperties): DatabaseKey =
            DatabaseKey(properties.servers().toSet(), properties.databaseName ?: "postgres")
    }
}
