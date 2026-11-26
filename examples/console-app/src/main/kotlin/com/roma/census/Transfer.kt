package com.roma.census

import io.github.octaviusframework.client.OctaviusClient

/**
 * Moves a citizen to another tribe and enters the nota that says so, in one transaction block. The block
 * reads the tribe it is moving him from, under a row lock, and decides on what it read.
 */
data class Transfer(val id: Int, val tribe: String) : Command {

    override fun run(db: OctaviusClient) {
        val nota = db.transaction {
            val citizen = Citizen.lock(this, id)
            if (citizen.tribe == tribe) throw Refused("#$id is in the tribe $tribe already")

            update("citizens").setValue("tribe").where("id = @id").executeUpdate("tribe" to tribe, "id" to id)
            TribeTransfer(from = citizen.tribe, to = tribe).also { it.enter(this, id) }
        }
        println("#$id ${nota.describe()}")
    }

    companion object {
        fun parse(arguments: Arguments) = Transfer(arguments.id(), arguments.required("tribe"))
    }
}
