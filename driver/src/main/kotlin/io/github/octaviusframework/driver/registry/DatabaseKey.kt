package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.properties.ServerAddress

/**
 * The database a [CatalogHolder] belongs to: its servers, in any order, and its name. Nothing else of the URL, which
 * would split the catalog and keep a password in a map key.
 */
internal data class DatabaseKey(val servers: Set<ServerAddress>, val database: String) {

    /** `host:port/database`, or `host:port,host:port/database` for several. */
    override fun toString(): String = "${servers.sorted().joinToString(",")}/$database"

    companion object {
        fun from(properties: OctaviusProperties): DatabaseKey =
            DatabaseKey(properties.servers().toSet(), properties.databaseName ?: "postgres")
    }
}
