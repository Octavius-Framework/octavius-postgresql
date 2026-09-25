package io.github.octaviusframework.driver.query

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What a terminal pins, and when.
 *
 * Every claim here is about the catalog an execution reads: a registration reaches the terminals that start
 * after it, and a result stays with the catalog its own terminal pinned.
 */
class ExecutionCatalogIntegrationTest : AbstractIntegrationTest() {

    /**
     * A target nothing else asks for, so that the converter this class registers on the session - global to the
     * database for as long as the class runs - decides only what these tests read.
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

    @BeforeAll
    fun registerOnTheSession() {
        openSession().use { it.typeManager.registerResultConverter(MarkerConverter("session")) }
    }

    @Test
    fun `a converter registered after the query was built reaches its first terminal`() {
        openSession().use { session ->
            val query = session.createNativeQuery("SELECT 'aquila'::text")
            session.typeManager.registerResultConverter(LateMarkerConverter())

            assertEquals(LateMarker("late:aquila"), query.fetchFieldStrict<LateMarker>())
        }
    }

    @Test
    fun `rows keep the catalog their own terminal pinned`() {
        openSession().use { session ->
            val query = session.createNativeQuery("SELECT 'vexillum'::text")
            val rows = query.fetchRows()

            // Registered after the rows came back, so it has no say over them.
            query.registerResultConverter(MarkerConverter("query"))

            assertEquals(Marker("session:vexillum"), rows.single().get<Marker>(0))
            assertEquals(Marker("query:vexillum"), query.fetchFieldStrict<Marker>())
        }
    }

    @Test
    fun `rows keep the query's own converters as their terminal found them`() {
        openSession().use { session ->
            val query = session.createNativeQuery("SELECT 'signum'::text")
                .registerResultConverter(MarkerConverter("first"))
            val rows = query.fetchRows()

            // Registered after the rows came back, beside one that was there before them.
            query.registerResultConverter(MarkerConverter("second"))

            assertEquals(Marker("first:signum"), rows.single().get<Marker>(0))
            assertEquals(Marker("second:signum"), query.fetchFieldStrict<Marker>())
        }
    }

    @Test
    fun `a query's own converter wins over the session's, for that query only`() {
        openSession().use { session ->
            val query = session.createNativeQuery("SELECT 'fasces'::text")
                .registerResultConverter(MarkerConverter("query"))

            assertEquals(Marker("query:fasces"), query.fetchFieldStrict<Marker>())
            assertEquals(Marker("session:fasces"), session.createNativeQuery("SELECT 'fasces'::text").fetchFieldStrict<Marker>())
        }
    }
}
