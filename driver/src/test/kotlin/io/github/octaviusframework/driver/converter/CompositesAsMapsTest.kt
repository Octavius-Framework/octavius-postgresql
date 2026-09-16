package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.result.array.CollectionArrayConverter
import io.github.octaviusframework.driver.converter.result.composite.MapCompositeConverter
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.composite.compositesAsMaps
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers [compositesAsMaps]: reading composites as maps for one query, whatever they are registered as.
 *
 * The registries are built by hand rather than by opening a session, because what is under test is which
 * converter claims a value - the query's registry sitting ahead of the session's, which is the whole reason
 * this can override a registration without touching one.
 */
class CompositesAsMapsTest {

    data class Tribute(val amount: Int, val currency: String)
    data class Assessment(val label: String, val payload: Tribute)

    private val text = PgType.Base(1, "text", "public")
    private val int4 = PgType.Base(2, "int4", "public")

    private val tribute = PgType.Composite(3, "tribute", "public", linkedMapOf("amount" to 2, "currency" to 1))
    private val assessment = PgType.Composite(4, "assessment", "public", linkedMapOf("label" to 1, "payload" to 3))

    /** Registered nowhere, and never given a Kotlin class: the composite you chose not to model. */
    private val leaf = PgType.Composite(5, "leaf", "public", linkedMapOf("note" to 1))
    private val parcel = PgType.Composite(6, "parcel", "public", linkedMapOf("title" to 1, "leaf" to 5))

    private val tributeArray = PgType.Array(7, "_tribute", "public", 3)

    private val typeRegistry = CatalogHolder().apply {
        update { it.withTypes(
            mapOf(
                1 to text, 2 to int4, 3 to tribute, 4 to assessment,
                5 to leaf, 6 to parcel, 7 to tributeArray
            )
        ) }
    }

    private val typeManager = TypeManager(typeRegistry).apply {
        registerAutoComposite<Tribute>("tribute", "public")
        registerAutoComposite<Assessment>("assessment", "public")
    }

    /** What a session holds: a composite becomes its registered class, or a map where one is asked for. */
    private val sessionMapper = ResultMapper(typeRegistry.catalog, null, typeManager.lookup)

    /** A query's own converters, ahead of the session's exactly as an execution puts them. */
    private fun queryMapper(vararg converters: ResultConverter<*, *>): ResultMapper =
        ResultMapper(typeRegistry.catalog, converters.toList(), typeManager.lookup)

    private fun composite(type: PgType.Composite, vararg values: Any?) = PgComposite(type, arrayOf(*values))

    private val anAssessment
        get() = composite(assessment, "census", composite(tribute, 40, "denarius"))

    // --- Every composite -----------------------------------------------------------------------------

    @Test
    fun `a composite and everything under it comes back as a map`() {
        val mapped: Map<String, Any?> = queryMapper(compositesAsMaps())
            .deserialize(anAssessment, typeOf<Map<String, Any?>>(), assessment)

        assertEquals("census", mapped["label"])
        assertEquals(mapOf("amount" to 40, "currency" to "denarius"), mapped["payload"])
    }

    @Test
    fun `without it the nested composite is the class it is registered as`() {
        // The contrast that makes the test above mean something: nothing changed about the registration, and
        // the session on its own still answers with the class.
        val mapped: Map<String, Any?> = sessionMapper
            .deserialize(anAssessment, typeOf<Map<String, Any?>>(), assessment)

        assertEquals(Tribute(40, "denarius"), mapped["payload"])
    }

    @Test
    fun `naming the class still gets the class`() {
        // Only `Any` and `Map` are claimed, so an explicit ask in the same query is untouched. What changes is
        // what a composite is by default, not what naming a class means.
        val assessed: Assessment = queryMapper(compositesAsMaps())
            .deserialize(anAssessment, typeOf<Assessment>(), assessment)

        assertEquals(Assessment("census", Tribute(40, "denarius")), assessed)
    }

    @Test
    fun `an array of composites comes back as a list of maps`() {
        val array = PgArray(
            arrayOid = 7,
            elementOid = 3,
            dimensions = listOf(ArrayDimension(2, 1)),
            elements = listOf(composite(tribute, 40, "denarius"), composite(tribute, 15, "sestertius"))
        )

        val mapped: List<Any> = queryMapper(compositesAsMaps())
            .deserialize(array, typeOf<List<Any>>(), tributeArray)

        assertEquals(
            listOf(
                mapOf("amount" to 40, "currency" to "denarius"),
                mapOf("amount" to 15, "currency" to "sestertius")
            ),
            mapped
        )
    }

    @Test
    fun `a composite registered nowhere collapses rather than leaking as a container`() {
        val aParcel = composite(parcel, "Gallia", composite(leaf, "surveyed"))

        val withConverter: Map<String, Any?> = queryMapper(compositesAsMaps())
            .deserialize(aParcel, typeOf<Map<String, Any?>>(), parcel)
        assertEquals(mapOf("note" to "surveyed"), withConverter["leaf"])

        // What it is without one, and the reason this is worth having: `Any?` finds no converter for an
        // unregistered composite, so the identity fallback hands back the driver's own container.
        val withoutConverter: Map<String, Any?> = sessionMapper
            .deserialize(aParcel, typeOf<Map<String, Any?>>(), parcel)
        assertIs<PgComposite>(withoutConverter["leaf"])
    }

    // --- One named type ------------------------------------------------------------------------------

    @Test
    fun `naming a type collapses that one and leaves the rest alone`() {
        val mapper = queryMapper(compositesAsMaps("tribute"))

        // The assessment is not the named type, so `Any` still reaches the reflective converter...
        val asked: Any = mapper.deserialize(anAssessment, typeOf<Any>(), assessment)
        assertEquals(Assessment("census", Tribute(40, "denarius")), asked)

        // ...while the tribute inside it collapses wherever it is met.
        val mapped: Map<String, Any?> = mapper.deserialize(anAssessment, typeOf<Map<String, Any?>>(), assessment)
        assertEquals(mapOf("amount" to 40, "currency" to "denarius"), mapped["payload"])
    }

    @Test
    fun `several names in one converter answer for all of them`() {
        val mapper = queryMapper(
            compositesAsMaps(listOf(QualifiedName("", "leaf"), QualifiedName("public", "tribute")))
        )

        val mapped: Map<String, Any?> = mapper.deserialize(anAssessment, typeOf<Map<String, Any?>>(), assessment)
        assertEquals(mapOf("amount" to 40, "currency" to "denarius"), mapped["payload"])

        val aParcel = composite(parcel, "Gallia", composite(leaf, "surveyed"))
        val parcelled: Map<String, Any?> = mapper.deserialize(aParcel, typeOf<Map<String, Any?>>(), parcel)
        assertEquals(mapOf("note" to "surveyed"), parcelled["leaf"])

        // And the one that was not named is still the class it is registered as.
        val asked: Any = mapper.deserialize(anAssessment, typeOf<Any>(), assessment)
        assertEquals(Assessment("census", Tribute(40, "denarius")), asked)
    }

    @Test
    fun `a stated schema has to be the one the value came from`() {
        val mapped: Map<String, Any?> = queryMapper(compositesAsMaps("tribute", "imperium"))
            .deserialize(anAssessment, typeOf<Map<String, Any?>>(), assessment)

        assertEquals(Tribute(40, "denarius"), mapped["payload"])
    }
}
