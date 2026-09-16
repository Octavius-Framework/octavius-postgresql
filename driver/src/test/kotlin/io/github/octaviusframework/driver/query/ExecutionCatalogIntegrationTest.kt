package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.type.PgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What a terminal pins, and when.
 *
 * Every claim here is about the catalog an execution reads: a registration reaches the terminals that start
 * after it, and a result stays with the catalog its own terminal pinned.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionCatalogIntegrationTest {

    /**
     * Targets nothing else in the suite asks for. A session registration is global to the database, so a
     * converter registered here would reach every other test were it keyed on anything they read.
     */
    data class Marker(val value: String)

    data class LateMarker(val value: String)

    private class MarkerConverter(private val tag: String) : ResultConverter<String, Marker> {
        override val supportedSourceClass = String::class

        override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            expectedType.classifier == Marker::class

        override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            Marker("$tag:$source")
    }

    private class LateMarkerConverter : ResultConverter<String, LateMarker> {
        override val supportedSourceClass = String::class

        override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            expectedType.classifier == LateMarker::class

        override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            LateMarker("late:$source")
    }

    private fun session(): OctaviusSession =
        getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")

    @BeforeAll
    fun registerOnTheSession() {
        session().use { it.typeManager.registerResultConverter(MarkerConverter("session")) }
    }

    @Test
    fun `a converter registered after the query was built reaches its first terminal`() {
        session().use { session ->
            val query = session.createNativeQuery("SELECT 'x'::text")
            session.typeManager.registerResultConverter(LateMarkerConverter())

            assertEquals(LateMarker("late:x"), query.fetchFieldStrict<LateMarker>())
        }
    }

    @Test
    fun `rows keep the catalog their own terminal pinned`() {
        session().use { session ->
            val query = session.createNativeQuery("SELECT 'y'::text")
            val rows = query.fetchRows()

            // Registered after the rows came back, so it has no say over them.
            query.registerResultConverter(MarkerConverter("query"))

            assertEquals(Marker("session:y"), rows.single().get<Marker>(0))
            assertEquals(Marker("query:y"), query.fetchFieldStrict<Marker>())
        }
    }

    @Test
    fun `a query's own converter wins over the session's, for that query only`() {
        session().use { session ->
            val query = session.createNativeQuery("SELECT 'z'::text")
                .registerResultConverter(MarkerConverter("query"))

            assertEquals(Marker("query:z"), query.fetchFieldStrict<Marker>())
            assertEquals(Marker("session:z"), session.createNativeQuery("SELECT 'z'::text").fetchFieldStrict<Marker>())
        }
    }
}
