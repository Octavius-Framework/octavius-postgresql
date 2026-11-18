package io.github.octaviusframework.serializer

import io.github.octaviusframework.identifier.CaseConvention
import io.github.octaviusframework.type.BigDecimal
import io.github.octaviusframework.type.datetime.DISTANT_FUTURE
import io.github.octaviusframework.type.datetime.DISTANT_PAST
import io.github.octaviusframework.type.datetime.MAX
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class BigDecimalAsNumberSerializerTest {

    @Test
    fun `writes a bare JSON number, not a string`() {
        val json = octaviusJson.encodeToString(Assessment.serializer(), Assessment(BigDecimal("120.50")))
        assertEquals("""{"denarii":120.50}""", json)
    }

    @Test
    fun `keeps digits a Double would round away`() {
        // 20 significant digits: more than the 15-17 a binary64 carries, so a serializer routing through
        // Double would come back with a different number here.
        val exact = "12345678901234567890.123456789"
        val decoded = octaviusJson.decodeFromString(
            Assessment.serializer(),
            """{"denarii":$exact}"""
        )
        assertEquals(exact, decoded.denarii.toString())
    }

    @Test
    fun `round-trips through the module`() {
        val original = Assessment(BigDecimal("0.000000000000000000001"))
        val decoded = octaviusJson.decodeFromString(
            Assessment.serializer(),
            octaviusJson.encodeToString(Assessment.serializer(), original)
        )
        assertEquals(original.denarii.toString(), decoded.denarii.toString())
    }

    @Test
    fun `a plain Json has no serializer for it`() {
        // The reason octaviusSerializersModule exists: without it a @Contextual BigDecimal does not resolve.
        assertFailsWith<SerializationException> {
            Json.encodeToString(Assessment.serializer(), Assessment(BigDecimal("1")))
        }
    }

    @Serializable
    data class Assessment(@Contextual val denarii: BigDecimal)
}

class InfinitySerializerTest {

    @Test
    fun `LocalDate infinities are written as PostgreSQL writes them`() {
        assertEquals(
            """{"until":"infinity"}""",
            octaviusJson.encodeToString(Grant.serializer(), Grant(LocalDate.DISTANT_FUTURE))
        )
        assertEquals(
            """{"until":"-infinity"}""",
            octaviusJson.encodeToString(Grant.serializer(), Grant(LocalDate.DISTANT_PAST))
        )
    }

    @Test
    fun `an ordinary LocalDate is still ISO-8601`() {
        assertEquals(
            """{"until":"0044-03-15"}""",
            octaviusJson.encodeToString(Grant.serializer(), Grant(LocalDate(44, 3, 15)))
        )
    }

    @Test
    fun `LocalDate infinities are read back as the same constants`() {
        assertEquals(
            LocalDate.DISTANT_FUTURE,
            octaviusJson.decodeFromString(Grant.serializer(), """{"until":"infinity"}""").until
        )
        assertEquals(
            LocalDate.DISTANT_PAST,
            octaviusJson.decodeFromString(Grant.serializer(), """{"until":"-infinity"}""").until
        )
    }

    @Test
    fun `LocalDateTime infinities round-trip`() {
        val encoded = octaviusJson.encodeToString(Census.serializer(), Census(LocalDateTime.DISTANT_FUTURE))
        assertEquals("""{"taken":"infinity"}""", encoded)
        assertEquals(
            LocalDateTime.DISTANT_FUTURE,
            octaviusJson.decodeFromString(Census.serializer(), encoded).taken
        )
    }

    @Test
    fun `Instant infinities round-trip`() {
        val encoded = octaviusJson.encodeToString(Edict.serializer(), Edict(Instant.DISTANT_PAST))
        assertEquals("""{"issued":"-infinity"}""", encoded)
        assertEquals(
            Instant.DISTANT_PAST,
            octaviusJson.decodeFromString(Edict.serializer(), encoded).issued
        )
    }

    @Test
    fun `without the module the default serializer writes a year PostgreSQL refuses`() {
        // The failure this replaces: +999999999-12-31 is not 'infinity', and ::date rejects it.
        val plain = Json.encodeToString(Grant.serializer(), Grant(LocalDate.DISTANT_FUTURE))
        assertEquals("""{"until":"+999999999-12-31"}""", plain)
    }

    @Serializable
    data class Grant(@Contextual val until: LocalDate)

    @Serializable
    data class Census(@Contextual val taken: LocalDateTime)

    @Serializable
    data class Edict(@Contextual val issued: Instant)
}

class EndOfDaySerializerTest {

    @Test
    fun `the end of the day is written as PostgreSQL writes it and read back as the same constant`() {
        val encoded = octaviusJson.encodeToString(Vigil.serializer(), Vigil(LocalTime.MAX))
        assertEquals("""{"until":"24:00:00"}""", encoded)
        assertEquals(LocalTime.MAX, octaviusJson.decodeFromString(Vigil.serializer(), encoded).until)
    }

    @Test
    fun `an ordinary time reads in PostgreSQL's spelling and round-trips`() {
        val halfPast = LocalTime(12, 30, 0, 500_000_000)
        assertEquals(halfPast, octaviusJson.decodeFromString(Vigil.serializer(), """{"until":"12:30:00.5"}""").until)
        assertEquals(
            halfPast,
            octaviusJson.decodeFromString(
                Vigil.serializer(),
                octaviusJson.encodeToString(Vigil.serializer(), Vigil(halfPast))
            ).until
        )
    }

    @Test
    fun `without the module the default serializer cannot read the end of the day`() {
        // What to_jsonb writes for a time column holding the end of the day.
        assertFails { Json.decodeFromString(Vigil.serializer(), """{"until":"24:00:00"}""") }
    }

    @Serializable
    data class Vigil(@Contextual val until: LocalTime)
}

class EnumWithCaseConventionSerializerTest {

    enum class Magistrature { Quaestor, Aedile, Praetor, Consul }

    object MagistratureSerializer : EnumWithCaseConventionSerializer<Magistrature>(
        enumName = "Magistrature",
        entries = Magistrature.entries
    )

    object LowercaseMagistratureSerializer : EnumWithCaseConventionSerializer<Magistrature>(
        enumName = "LowercaseMagistrature",
        entries = Magistrature.entries,
        pgConvention = CaseConvention.SNAKE_CASE_LOWER
    )

    @Serializable
    data class Appointment(@Serializable(with = MagistratureSerializer::class) val office: Magistrature)

    @Test
    fun `writes the label the enum column holds, not the Kotlin name`() {
        assertEquals(
            """{"office":"PRAETOR"}""",
            Json.encodeToString(Appointment.serializer(), Appointment(Magistrature.Praetor))
        )
    }

    @Test
    fun `reads the label back into the constant`() {
        assertEquals(
            Magistrature.Consul,
            Json.decodeFromString(Appointment.serializer(), """{"office":"CONSUL"}""").office
        )
    }

    @Test
    fun `the pg convention is what decides the label`() {
        assertEquals(
            "\"praetor\"",
            Json.encodeToString(LowercaseMagistratureSerializer, Magistrature.Praetor)
        )
    }

    @Test
    fun `a label no constant answers to is a SerializationException`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(Appointment.serializer(), """{"office":"CENSOR"}""")
        }
    }
}
