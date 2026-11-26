package com.roma.census

import io.github.octaviusframework.annotation.DynamicallyMappable
import io.github.octaviusframework.client.OctaviusClient
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A censor's mark against a citizen. Every implementation goes into the one `notae.nota` column as a
 * `dynamic_dto`, and reads back as the class its row's `type_name` names.
 */
sealed interface Nota {
    fun describe(): String

    /** Enters this nota against citizen #[citizenId]. */
    fun enter(db: OctaviusClient, citizenId: Int) {
        db.insertInto("notae").values(listOf("citizen_id", "nota"))
            .executeUpdate("citizen_id" to citizenId, "nota" to this)
    }
}

/** Moved from one tribe to another. */
@Serializable
@DynamicallyMappable("tribe_transfer")
data class TribeTransfer(val from: String, val to: String) : Nota {
    override fun describe() = "moved from the tribe $from to $to"
}

/** Struck down to a lower order. `@Contextual` writes the orders under their labels, `SENATORIUS`. */
@Serializable
@DynamicallyMappable("demotion")
data class Demotion(@Contextual val from: Ordo, @Contextual val to: Ordo, val reason: String) : Nota {
    override fun describe() = "struck from $from to $to: $reason"
}
