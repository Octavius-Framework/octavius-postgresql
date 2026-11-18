package io.github.octaviusframework.serializer

import io.github.octaviusframework.type.datetime.MAX
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How PostgreSQL writes the end of the day, the one `time` no [LocalTime] holds. */
private const val END_OF_DAY = "24:00:00"

/**
 * Writes a [LocalTime] the way a `time` column holds it, so that a payload built from a `time` column reads back.
 *
 * A `time` column can hold `24:00:00`, the end of the day, which the driver reads as [LocalTime.Companion.MAX].
 * `to_jsonb` writes it out as `"24:00:00"`, and the default serializer refuses an hour of 24. This writes
 * [LocalTime.Companion.MAX] as `"24:00:00"` and reads it back as the same constant; every other time is ISO-8601.
 *
 * Registered contextually by [octaviusSerializersModule]; `@Contextual` on the property is what selects it.
 */
object LocalTimeWithEndOfDaySerializer : KSerializer<LocalTime> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "io.github.octaviusframework.serializer.LocalTimeWithEndOfDaySerializer",
        PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(if (value == LocalTime.MAX) END_OF_DAY else value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalTime {
        val string = decoder.decodeString()
        return if (string == END_OF_DAY) LocalTime.MAX else LocalTime.parse(string)
    }
}
