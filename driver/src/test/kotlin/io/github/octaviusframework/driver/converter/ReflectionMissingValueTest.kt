package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.result.composite.ReflectionCompositeConverter
import io.github.octaviusframework.driver.converter.result.mapper.ResultMapper
import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.registry.TypeManager
import io.github.octaviusframework.driver.registry.CatalogHolder
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.util.reflection.toDataObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.typeOf

/**
 * A default value stands in for an *absent* attribute only. SQL `NULL` is a value: it reaches a nullable property
 * as `null` and fails a non-nullable one, whether or not a default is declared.
 *
 * |                        | attribute absent             | present, value is NULL       |
 * |------------------------|------------------------------|------------------------------|
 * | `String`               | REQUIRED_ATTRIBUTE_MISSING   | REQUIRED_ATTRIBUTE_MISSING   |
 * | `String?`              | null                         | null                         |
 * | `String = "unknown"`   | "unknown"                    | REQUIRED_ATTRIBUTE_MISSING   |
 * | `String? = "unknown"`  | "unknown"                    | null                         |
 */
class ReflectionMissingValueTest {

    data class Required(val cognomen: String, val province: String)
    data class Nullable(val cognomen: String, val province: String?)
    data class Defaulted(val cognomen: String, val province: String = "unknown")
    data class NullableDefaulted(val cognomen: String, val province: String? = "unknown")

    private val textOid = 1

    /** Declares `province` - the value stored in it may still be NULL. */
    private val withProvince = PgType.Composite(
        10, "with_province", "public",
        LinkedHashMap(mapOf("cognomen" to textOid, "province" to textOid))
    )

    /** Does not declare `province` at all. */
    private val withoutProvince = PgType.Composite(
        11, "without_province", "public",
        LinkedHashMap(mapOf("cognomen" to textOid))
    )

    private val registry = CatalogHolder().apply {
        update { it.withTypes(
            mapOf(
                textOid to PgType.Base(textOid, "text", "public"),
                10 to withProvince,
                11 to withoutProvince
            )
        ) }
    }

    private val typeManager = TypeManager(registry).apply {
        registerAutoComposite<Required>("required_t", "public")
        registerAutoComposite<Nullable>("nullable_t", "public")
        registerAutoComposite<Defaulted>("defaulted_t", "public")
        registerAutoComposite<NullableDefaulted>("nullable_defaulted_t", "public")
    }

    private val mapper = ResultMapper(registry.catalog, listOf(ReflectionCompositeConverter), typeManager.lookup)

    private fun composite(type: PgType.Composite, values: Map<String, Any?>) =
        PgComposite(type, type.attributes.map { (name, _) -> values[name] }.toTypedArray())

    private inline fun <reified T : Any> fromComposite(type: PgType.Composite, values: Map<String, Any?>): T =
        mapper.deserialize(composite(type, values), typeOf<T>(), type)

    private fun assertMissingProvince(block: () -> Any?) {
        val ex = assertThrows<MappingException> { block() }
        assertEquals(MappingExceptionReason.REQUIRED_ATTRIBUTE_MISSING, ex.reason)
        assertTrue(ex.path.contains("province"), "expected 'province' in path, got ${ex.path}")
    }

    // --- composite: attribute absent from the type -------------------------

    @Test
    fun `absent attribute, required property, fails`() {
        assertMissingProvince { fromComposite<Required>(withoutProvince, mapOf("cognomen" to "Cicero")) }
    }

    @Test
    fun `absent attribute, nullable property, is null`() {
        val r = fromComposite<Nullable>(withoutProvince, mapOf("cognomen" to "Cicero"))
        assertEquals("Cicero", r.cognomen)
        assertNull(r.province)
    }

    @Test
    fun `absent attribute, defaulted property, uses the default`() {
        val r = fromComposite<Defaulted>(withoutProvince, mapOf("cognomen" to "Cicero"))
        assertEquals("unknown", r.province)
    }

    @Test
    fun `absent attribute, nullable defaulted property, uses the default`() {
        val r = fromComposite<NullableDefaulted>(withoutProvince, mapOf("cognomen" to "Cicero"))
        assertEquals("unknown", r.province)
    }

    // --- composite: attribute present, value is NULL ------------------------

    @Test
    fun `null value, required property, fails`() {
        assertMissingProvince {
            fromComposite<Required>(withProvince, mapOf("cognomen" to "Cicero", "province" to null))
        }
    }

    @Test
    fun `null value, nullable property, is null`() {
        val r = fromComposite<Nullable>(withProvince, mapOf("cognomen" to "Cicero", "province" to null))
        assertNull(r.province)
    }

    @Test
    fun `null value, defaulted property, fails instead of using the default`() {
        assertMissingProvince {
            fromComposite<Defaulted>(withProvince, mapOf("cognomen" to "Cicero", "province" to null))
        }
    }

    @Test
    fun `null value, nullable defaulted property, is null instead of the default`() {
        val r = fromComposite<NullableDefaulted>(withProvince, mapOf("cognomen" to "Cicero", "province" to null))
        assertNull(r.province)
    }

    @Test
    fun `present value always wins over a default`() {
        val r = fromComposite<Defaulted>(withProvince, mapOf("cognomen" to "Cicero", "province" to "Latium"))
        assertEquals("Latium", r.province)
    }

    // --- the same matrix through Map toDataObject --------------------------

    private val absent = mapOf<String, Any?>("cognomen" to "Cicero")
    private val nulled = mapOf<String, Any?>("cognomen" to "Cicero", "province" to null)

    @Test
    fun `toDataObject - absent key`() {
        assertMissingProvince { absent.toDataObject<Required>() }
        assertNull(absent.toDataObject<Nullable>().province)
        assertEquals("unknown", absent.toDataObject<Defaulted>().province)
        assertEquals("unknown", absent.toDataObject<NullableDefaulted>().province)
    }

    // --- registration rejects anything reflection cannot take apart ---------

    class NotAData(val cognomen: String, val province: String)

    @Test
    fun `registerAutoComposite rejects a non-data class`() {
        val ex = assertThrows<InvalidOperationException> {
            typeManager.registerAutoComposite<NotAData>("not_a_data_t", "public")
        }
        assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, ex.reason)
        assertTrue(
            ex.details?.contains("NotAData") == true,
            "expected the rejected class in details, got ${ex.details}"
        )
    }

    @Test
    fun `a rejected registration leaves the registry untouched`() {
        val before = typeManager.catalog.registeredComposites.size
        val namesBefore = typeManager.catalog.compositeClassByName.size
        assertThrows<InvalidOperationException> {
            typeManager.registerAutoComposite<NotAData>("not_a_data_t", "public")
        }
        assertEquals(before, typeManager.catalog.registeredComposites.size)
        assertEquals(namesBefore, typeManager.catalog.compositeClassByName.size)
    }

    @Test
    fun `toDataObject - key present holding null`() {
        assertMissingProvince { nulled.toDataObject<Required>() }
        assertNull(nulled.toDataObject<Nullable>().province)
        assertMissingProvince { nulled.toDataObject<Defaulted>() }
        assertNull(nulled.toDataObject<NullableDefaulted>().province)
    }
}
