package io.github.octaviusframework.client

import io.github.octaviusframework.client.dynamic.DYNAMIC_DTO_DDL
import io.github.octaviusframework.client.dynamic.DynamicWriteStrategy
import io.github.octaviusframework.driver.exception.MappingException
import io.github.octaviusframework.driver.exception.MappingExceptionReason
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.type.withPgType
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [DynamicWriteStrategy]: when an unwrapped instance of a registered class is written as a
 * `dynamic_dto`, and what wrapping overrides.
 *
 * Every test builds its own client on the mode under test, and what it registers belongs to the database rather
 * than to that client: a class an earlier test registered would still be registered on that test's mode, and
 * registering it again on another one is refused. So each test also drops the registry on the way in and the
 * way out, and every mode is tested on registrations of its own.
 */
class DynamicWriteStrategyTest : AbstractIntegrationTest() {

    /** Registered as a dynamic type and as nothing else: the unambiguous case. */
    @Serializable
    data class Triumph(val general: String, val enemy: String)

    /** Registered both ways, which is the only case the three modes disagree about. */
    @Serializable
    data class Honour(val title: String, val year: Int)

    /**
     * A composite whose two attributes declare one destination each: [award] a `public.honour`, [citation] a
     * `public.dynamic_dto`. Both attribute types are known from the catalogue, so neither is a case any mode
     * has a say in.
     */
    data class Decoration(val award: Honour, val citation: Triumph)

    override val schema = DYNAMIC_DTO_DDL + ";\n" + """
        CREATE TYPE public.honour AS (title text, year int);
        CREATE TYPE public.decoration AS (award public.honour, citation public.dynamic_dto);
        CREATE TABLE dyn_strategy (
            id            SERIAL PRIMARY KEY,
            as_dynamic    public.dynamic_dto,
            as_composite  public.honour,
            as_composites public.honour[],
            as_nested     public.decoration
        );
    """.trimIndent()

    @BeforeEach
    fun clearTable() {
        openSession().use { it.createNativeQuery("TRUNCATE dyn_strategy RESTART IDENTITY").execute() }
    }

    /**
     * Runs [block] against a client on [strategy], with both classes registered and the registry dropped
     * either side so no converter outlives the test that installed it.
     */
    private fun withStrategy(strategy: DynamicWriteStrategy, block: (OctaviusClient) -> Unit) {
        GlobalCatalogStore.removeCatalog(TestDatabase.URL)
        try {
            TestDatabase.dataSource().use { ds ->
                OctaviusClient.fromDataSource(ds, dynamicWriteStrategy = strategy).use { db ->
                    db.dynamicTypes.register<Triumph>("triumph")
                    db.dynamicTypes.register<Honour>("honour_dyn")
                    db.execute {
                        typeManager.registerAutoComposite<Honour>("honour", "public")
                        typeManager.registerAutoComposite<Decoration>("decoration", "public")
                    }
                    block(db)
                }
            }
        } finally {
            GlobalCatalogStore.removeCatalog(TestDatabase.URL)
        }
    }

    private fun OctaviusClient.writeDynamic(value: Any) =
        insertInto("dyn_strategy").values(listOf("as_dynamic")).update("as_dynamic" to value)

    private fun OctaviusClient.readDynamic() =
        select("as_dynamic").from("dyn_strategy").fetchFieldStrict<Any>()

    // --- The unambiguous class, which is nearly all of them ---------------------------------------

    @Test
    fun `automatic writes a registered class without wrapping`() {
        withStrategy(DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS) { db ->
            val triumph = Triumph("Scipio", "Carthage")
            db.writeDynamic(triumph)

            assertEquals(triumph, db.readDynamic())
        }
    }

    @Test
    fun `prefer writes it without wrapping as well`() {
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            val triumph = Triumph("Scipio", "Carthage")
            db.writeDynamic(triumph)

            assertEquals(triumph, db.readDynamic())
        }
    }

    @Test
    fun `explicit only refuses it unwrapped and names the wrapper`() {
        withStrategy(DynamicWriteStrategy.EXPLICIT_ONLY) { db ->
            val thrown = assertFailsWith<MappingException> {
                db.writeDynamic(Triumph("Scipio", "Carthage"))
            }

            assertTrue(
                thrown.details.contains("toDynamicDto"),
                "the message should point at the wrapper, not at a missing codec: ${thrown.details}"
            )
            assertTrue(thrown.details.contains("Triumph"))
        }
    }

    @Test
    fun `explicit only writes it once wrapped`() {
        withStrategy(DynamicWriteStrategy.EXPLICIT_ONLY) { db ->
            val triumph = Triumph("Scipio", "Carthage")
            db.writeDynamic(db.dynamicTypes.toDynamicDto(triumph))

            assertEquals(triumph, db.readDynamic())
        }
    }

    // --- The class registered both ways, which is what the modes disagree about -------------------

    @Test
    fun `automatic leaves a class that is also a composite to the composite`() {
        withStrategy(DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS) { db ->
            val honour = Honour("Corona Civica", 47)
            db.insertInto("dyn_strategy").values(listOf("as_composite"))
                .update("as_composite" to honour)

            assertEquals(
                honour,
                db.select("as_composite").from("dyn_strategy").fetchFieldStrict<Honour>()
            )
        }
    }

    @Test
    fun `wrapping is how that class reaches the dynamic column under automatic`() {
        withStrategy(DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS) { db ->
            val honour = Honour("Corona Civica", 47)
            db.writeDynamic(db.dynamicTypes.toDynamicDto(honour))

            assertEquals(honour, db.readDynamic())
        }
    }

    @Test
    fun `prefer takes that class for the dynamic form instead`() {
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            val honour = Honour("Corona Civica", 47)
            // Unwrapped, and into the dynamic_dto column: under AUTOMATIC the composite path would claim it
            // and the server would refuse a public.honour here.
            db.writeDynamic(honour)

            assertEquals(honour, db.readDynamic())
        }
    }

    @Test
    fun `automatic really does hand that class to the composite path`() {
        // The other half of the pair above, and what makes it discriminate: the same unwrapped value into the
        // same column fails under AUTOMATIC, because what arrives is a public.honour rather than a dynamic_dto.
        withStrategy(DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS) { db ->
            assertFailsWith<OctaviusException> {
                db.writeDynamic(Honour("Corona Civica", 47))
            }
        }
    }

    // --- Where the destination names its own type, which is not a case a mode answers -------------

    @Test
    fun `an attribute takes the type its composite declares, not the mode`() {
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            // Both attribute types come from the catalogue, so neither is ambiguous and neither is the mode's
            // to answer: the class registered both ways goes to `award` as a public.honour even under the mode
            // that would otherwise take it for the dynamic form, and the class registered only as a dynamic
            // type goes to `citation` as the dynamic_dto it is declared to be. A mode reaching in here would
            // put a dynamic_dto where honour was declared, and the server would refuse the row.
            val decoration = Decoration(Honour("Corona Civica", 47), Triumph("Scipio", "Carthage"))
            db.insertInto("dyn_strategy").values(listOf("as_nested")).update("as_nested" to decoration)

            assertEquals(
                decoration,
                db.select("as_nested").from("dyn_strategy").fetchFieldStrict<Decoration>()
            )
        }
    }

    @Test
    fun `an array element takes the type the array declares`() {
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            val honours = listOf(Honour("Corona Civica", 47), Honour("Corona Muralis", 52))
            db.insertInto("dyn_strategy").values(listOf("as_composites"))
                .update("as_composites" to honours.withPgType("honour", "public", isArray = true))

            assertEquals(
                honours,
                db.select("as_composites").from("dyn_strategy").fetchFieldStrict<List<Honour>>()
            )
        }
    }

    @Test
    fun `a class that does not belong in the named type is refused before the wire`() {
        // Triumph is a registered dynamic type and nothing else, so under PREFER_DYNAMIC_DTO it is exactly the
        // value the mode would have claimed. The named type declines it instead, and says so here rather than
        // letting a dynamic_dto reach a column declared public.honour.
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            val thrown = assertFailsWith<MappingException> {
                db.insertInto("dyn_strategy").values(listOf("as_composite"))
                    .update("as_composite" to Triumph("Scipio", "Carthage").withPgType("honour", "public"))
            }

            assertEquals(MappingExceptionReason.NO_CONVERTER_FOUND, thrown.reason)
            assertTrue(
                thrown.details.contains("Triumph") && thrown.details.contains("honour"),
                "the message should name the class and the type it was sent to: ${thrown.details}"
            )
        }
    }

    @Test
    fun `naming the type is how the composite is reached under prefer`() {
        // The counterpart of `toDynamicDto`: that wrapper says "the dynamic form" under every mode, and naming
        // the type says the other one. Without it PREFER_DYNAMIC_DTO would be the one mode with a destination
        // nothing could reach.
        withStrategy(DynamicWriteStrategy.PREFER_DYNAMIC_DTO) { db ->
            val honour = Honour("Corona Civica", 47)
            db.insertInto("dyn_strategy").values(listOf("as_composite"))
                .update("as_composite" to honour.withPgType("honour", "public"))

            assertEquals(
                honour,
                db.select("as_composite").from("dyn_strategy").fetchFieldStrict<Honour>()
            )
        }
    }
}
