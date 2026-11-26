package com.roma.census

import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.join
import io.github.octaviusframework.client.query.withParam

/** Lists the rolls, narrowed by whichever filters were given. */
data class ListCitizens(val tribe: String?, val ordo: Ordo?, val city: String?, val limit: Long?) : Command {

    override fun run(db: OctaviusClient) {
        // A filter that was not given is null and drops out, and with none left there is no WHERE at all
        val filter = listOfNotNull(
            tribe?.let { "tribe = @tribe" withParam ("tribe" to it) },
            ordo?.let { "ordo = @ordo" withParam ("ordo" to it) },
            city?.let { "(residence).city = @city" withParam ("city" to it) }
        ).join(" AND ")

        val query = db.select("*").from("citizens").where(filter.sql).orderBy("id").limit(limit)

        val citizens = query.fetchObjects<Citizen>(filter.params)
        val total = query.toCountQuery().fetchFieldStrict<Long>(filter.params)

        citizens.forEach { println(it.describe()) }
        println("${citizens.size} of $total citizens")
    }

    companion object {
        fun parse(arguments: Arguments) = ListCitizens(
            tribe = arguments.optional("tribe"),
            ordo = arguments.ordo("ordo"),
            city = arguments.optional("city"),
            limit = arguments.long("limit")
        )
    }
}
