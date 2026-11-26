package com.roma.curia.domain

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import kotlin.uuid.Uuid


object UuidSerializer : ValueSerializer<Uuid>() {
    override fun serialize(value: Uuid, gen: JsonGenerator, serializers: SerializationContext) {
        gen.writeString(value.toString())
    }
}

object UuidDeserializer : ValueDeserializer<Uuid>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Uuid {
        return Uuid.parse(p.string)
    }
}

// Example composite types
data class Estate(val province: String, val nearestTown: String, val iugera: Int)
data class Dossier(val age: Int, val agnomen: String, val honours: Map<String, String>)

data class Senator(
    @field:JsonSerialize(using = UuidSerializer::class)
    @field:JsonDeserialize(using = UuidDeserializer::class)
    val id: Uuid? = null,
    val cognomen: String,
    val magistracy: Magistracy,
    val familySeat: Estate, // Single composite
    val countryEstates: List<Estate>, // Array of composites
    val dossier: Dossier // Nested/another composite
)

enum class Magistracy { CONSUL, PRAETOR, AEDILE, QUAESTOR }
