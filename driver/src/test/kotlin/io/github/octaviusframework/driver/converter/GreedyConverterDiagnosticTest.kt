package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A converter whose `canConvert` keys on the column type alone gets selected for every json/jsonb
 * value, whatever Kotlin type the caller asked for - so it ends up returning a [Dossier] to someone
 * who requested a `JsonObject`.
 *
 * Nothing about the interface can rule that out: `T` is erased, so selection rests entirely on
 * canConvert() being honest about `expectedType`. The mapper therefore compares the produced value
 * against the requested type. Without that, the mismatch would reach the caller as a bare
 * ClassCastException thrown from the caller's own line, with no frame naming the converter.
 */
class GreedyConverterDiagnosticTest : AbstractIntegrationTest() {

    data class Dossier(val raw: String)

    class GreedyDossierConverter : ResultConverter<String, Dossier> {
        override val supportedSourceClass = String::class

        // Deliberately wrong: says yes to every json/jsonb column, whatever was asked for.
        override fun canConvert(
            sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
        ): Boolean = sourceType.name == "json" || sourceType.name == "jsonb"

        override fun convert(
            source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext
        ): Dossier = Dossier(source)
    }

    override val schema = "CREATE TABLE greedy_converter_probe (data jsonb)"

    @Test
    fun `converter producing the wrong type fails as a MappingException naming it`() {
        val session = openSession()
        try {
            session.createNativeQuery("""INSERT INTO greedy_converter_probe VALUES ('{"a":1}')""").execute()

            // Scoped to this query, so the rest of the suite is unaffected - the same registration
            // made through typeManager would apply to every session on this database.
            val query = session.createNativeQuery("SELECT data FROM greedy_converter_probe")
                .registerResultConverter(GreedyDossierConverter())

            val row = query.fetchRowStrict()

            // Asking for what the converter actually produces still works
            assertEquals(Dossier("""{"a": 1}"""), row.get<Dossier>(0))

            // Asking for anything else is the driver's error, not a ClassCastException in our frame
            val e = assertFailsWith<MappingException> { row.get<JsonObject>(0) }
            assertEquals(MappingExceptionReason.CONVERSION_ERROR, e.reason)

            val message = e.getDetailedMessage()
            assertTrue(message.contains("GreedyDossierConverter"), "Should name the converter: $message")
            assertTrue(message.contains("Dossier"), "Should name what came back: $message")
            assertTrue(message.contains("JsonObject"), "Should name what was expected: $message")
        } finally {
            session.close()
        }
    }
}
