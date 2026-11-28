package com.roma.census

import io.github.octaviusframework.annotation.PgCompositeType
import io.github.octaviusframework.annotation.PgEnumType
import io.github.octaviusframework.client.OctaviusClient

/** The `ordo` enum: `Senatorius` is the label `SENATORIUS`. Declared from the highest down, as in V2. */
@PgEnumType
enum class Ordo { Senatorius, Equester, Plebeius }

/** The `residence` composite. */
@PgCompositeType
data class Residence(val city: String, val district: String)

/** A row of `citizens`, read by column name: `pater_id` fills [paterId]. */
data class Citizen(
    val id: Int,
    val nomen: String,
    val tribe: String,
    val ordo: Ordo,
    val residence: Residence,
    val assessment: Long,
    val paterId: Int?
) {
    fun describe(): String {
        val father = paterId?.let { ", son of #$it" } ?: ""
        return "#$id $nomen$father: tribe $tribe, $ordo, ${residence.city} (${residence.district}), $assessment HS"
    }

    companion object {
        /** Reads citizen #[id] and holds the row until the transaction around the call ends. */
        fun lock(db: OctaviusClient, id: Int): Citizen =
            db.select("*").from("citizens").where("id = @id").locking("FOR UPDATE")
                .fetchObject<Citizen>("id" to id)
                ?: throw Refused("No citizen #$id on the rolls")
    }
}
