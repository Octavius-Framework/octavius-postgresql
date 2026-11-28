package com.roma.census

import io.github.octaviusframework.client.OctaviusClient

/** Strikes a citizen down to a lower order and enters the nota that says why, in one transaction block. */
data class Demote(val id: Int, val ordo: Ordo, val reason: String) : Command {

    override fun run(db: OctaviusClient) {
        val nota = db.transaction {
            val citizen = Citizen.lock(this, id)
            // Declared from the highest down, so a lower order is a later constant
            if (ordo <= citizen.ordo) throw Refused("#$id is ${citizen.ordo}, and $ordo is not below it")

            update("citizens").setValue("ordo").where("id = @id").executeUpdate("ordo" to ordo, "id" to id)
            Demotion(from = citizen.ordo, to = ordo, reason = reason).also { it.enter(this, id) }
        }
        println("#$id ${nota.describe()}")
    }

    companion object {
        fun parse(arguments: Arguments) = Demote(
            id = arguments.id(),
            ordo = arguments.ordo("ordo") ?: throw UsageException("--ordo is required"),
            reason = arguments.required("reason")
        )
    }
}
