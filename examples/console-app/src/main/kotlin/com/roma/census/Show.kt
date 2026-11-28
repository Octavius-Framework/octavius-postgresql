package com.roma.census

import io.github.octaviusframework.client.OctaviusClient

/** One citizen and the notae against him, oldest first. */
data class Show(val id: Int) : Command {

    override fun run(db: OctaviusClient) {
        val citizen = db.select("*").from("citizens").where("id = @id")
            .fetchObject<Citizen>("id" to id)
            ?: throw Refused("No citizen #$id on the rolls")

        // Asked for as the sealed interface, each row decodes as the class its own type_name names
        val notae = db.select("nota").from("notae").where("citizen_id = @id").orderBy("id")
            .fetchFields<Nota>("id" to id)

        println(citizen.describe())
        notae.forEach { println("  nota: ${it.describe()}") }
    }

    companion object {
        fun parse(arguments: Arguments) = Show(arguments.id())
    }
}
