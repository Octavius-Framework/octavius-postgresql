package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * What a layer built on the driver can keep in the catalog, and that it has the scope the driver's own
 * registrations have: replaced whole, carried across a reload and a registration, dropped with the catalog.
 */
class CatalogAttachmentTest {

    private data class Tally(val names: List<String>)

    private object Unrelated : ResultConverter<String, String> {
        override val supportedSourceClass = String::class
        override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) = false
        override fun convert(source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext) = source
    }

    @Test
    fun `a value is read back under the class it was kept under`() {
        val holder = CatalogHolder()
        assertNull(holder.catalog.attachment(Tally::class))

        TypeManager(holder).attach(Tally::class) { Tally(listOf("first")) }

        assertEquals(Tally(listOf("first")), holder.catalog.attachment(Tally::class))
    }

    @Test
    fun `the transform is handed what was kept before`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)

        types.attach(Tally::class) { Tally(listOf("first")) }
        types.attach(Tally::class) { current -> Tally(current!!.names + "second") }

        assertEquals(Tally(listOf("first", "second")), holder.catalog.attachment(Tally::class))
    }

    @Test
    fun `a catalog taken before the value was kept does not see it`() {
        // Which is what makes it safe for a converter to read: the catalog an execution pinned answers the same
        // way for the whole of that execution.
        val holder = CatalogHolder()
        val pinned = holder.catalog

        TypeManager(holder).attach(Tally::class) { Tally(listOf("later")) }

        assertNull(pinned.attachment(Tally::class))
    }

    @Test
    fun `a reload and a registration carry it over`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        val kept = Tally(listOf("kept"))
        types.attach(Tally::class) { kept }

        holder.update { it.withTypes(emptyMap()) }
        types.registerResultConverter(Unrelated)

        assertSame(kept, holder.catalog.attachment(Tally::class))
    }

    @Test
    fun `a transform that throws leaves the catalog as it was`() {
        val holder = CatalogHolder()
        val types = TypeManager(holder)
        types.attach(Tally::class) { Tally(listOf("first")) }
        val before = holder.catalog

        assertThrows<IllegalStateException> {
            types.attach(Tally::class) { error("refused") }
        }

        assertSame(before, holder.catalog)
    }

    @Test
    fun `removing the catalog drops it`() {
        val key = DatabaseKey("attachment-test-host", 5432, "attachment_test")
        TypeManager(GlobalCatalogStore.holderFor(key)).attach(Tally::class) { Tally(listOf("gone")) }

        GlobalCatalogStore.removeCatalog("jdbc:octavius://attachment-test-host:5432/attachment_test")

        assertNull(GlobalCatalogStore.holderFor(key).catalog.attachment(Tally::class))
        GlobalCatalogStore.removeCatalog("jdbc:octavius://attachment-test-host:5432/attachment_test")
    }
}
