package io.github.octaviusframework.driver.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.RawQuery
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.identifier.QualifiedName
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.isKnownOid
import io.github.octaviusframework.serializer.octaviusJson
import kotlinx.serialization.Serializable
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType

/*
 * Four ways to put a structure in one column, each with a family of classes of its own.
 *
 * The families are not duplication for its own sake. `DynamicWriteStrategy.AUTOMATIC_WHEN_UNAMBIGUOUS` hands a
 * class that is also a registered composite to the composite path, and `registerAutoComposite` is registered
 * per class - so one shared family would leave rungs serving each other and the benchmark would report ties it
 * had arranged itself. The same reason `CompositeInsertBenchmark` keeps `SenatorReflect` apart from
 * `SenatorExplicit`.
 *
 * Every family exists in two shapes: nested, where `stats` is a structure of its own, and flat, where its
 * three fields sit beside `id` and `name`. The shape is a `@Param`, so each rung is measured in both.
 */

// -- Composite, read and written reflectively through registerAutoComposite ---------------------------

data class StatsAuto(val strength: Int, val agility: Int, val intelligence: Int)
data class CharacterAuto(val id: Int, val name: String, val stats: StatsAuto)
data class FlatAuto(val id: Int, val name: String, val strength: Int, val agility: Int, val intelligence: Int)

// -- Composite, read and written through the hand-written converters below ----------------------------

data class StatsManual(val strength: Int, val agility: Int, val intelligence: Int)
data class CharacterManual(val id: Int, val name: String, val stats: StatsManual)
data class FlatManual(val id: Int, val name: String, val strength: Int, val agility: Int, val intelligence: Int)

// -- dynamic_dto ---------------------------------------------------------------------------------------

@Serializable
data class StatsDynamic(val strength: Int, val agility: Int, val intelligence: Int)

@Serializable
data class CharacterDynamic(val id: Int, val name: String, val stats: StatsDynamic)

@Serializable
data class FlatDynamic(val id: Int, val name: String, val strength: Int, val agility: Int, val intelligence: Int)

// -- Plain jsonb ----------------------------------------------------------------------------------------

@Serializable
data class StatsJson(val strength: Int, val agility: Int, val intelligence: Int)

@Serializable
data class CharacterJson(val id: Int, val name: String, val stats: StatsJson)

@Serializable
data class FlatJson(val id: Int, val name: String, val strength: Int, val agility: Int, val intelligence: Int)

/*
 * The jsonb rungs' converters.
 *
 * Not something the library offers - they exist so the ladder has a rung between a composite and a
 * `dynamic_dto`, and they are deliberately the thinnest pair that can carry a JSON document: the same
 * `octaviusJson` a `dynamic_dto` payload goes through, and nothing else. What separates this rung from the
 * dynamic one is therefore the envelope alone.
 */

internal class CharacterJsonParameterConverter : ParameterConverter<CharacterJson> {
    override val supportedClass = CharacterJson::class
    override fun convert(source: CharacterJson, expectedOid: Int, context: SerializationContext): Any =
        octaviusJson.encodeToString(CharacterJson.serializer(), source)

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("pg_catalog", "jsonb")
}

internal class FlatJsonParameterConverter : ParameterConverter<FlatJson> {
    override val supportedClass = FlatJson::class
    override fun convert(source: FlatJson, expectedOid: Int, context: SerializationContext): Any =
        octaviusJson.encodeToString(FlatJson.serializer(), source)

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("pg_catalog", "jsonb")
}

internal object CharacterJsonResultConverter : ResultConverter<String, CharacterJson> {
    override val supportedSourceClass = String::class
    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = expectedType.classifier == CharacterJson::class

    override fun convert(
        source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): CharacterJson = octaviusJson.decodeFromString(CharacterJson.serializer(), source)
}

internal object FlatJsonResultConverter : ResultConverter<String, FlatJson> {
    override val supportedSourceClass = String::class
    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = expectedType.classifier == FlatJson::class

    override fun convert(
        source: String, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): FlatJson = octaviusJson.decodeFromString(FlatJson.serializer(), source)
}

/*
 * The hand-written composite converters.
 *
 * These are the control for reflection. `registerAutoComposite` discovers the attributes of a composite and
 * matches them to constructor parameters by name, on every value; these name the attributes outright. Reading
 * one, `stats` arrives as a `PgComposite` of its own, which is where the nested shape asks the question this
 * class exists for: what a second level costs when nothing is being discovered.
 */

internal object CharacterManualResultConverter : ResultConverter<PgComposite, CharacterManual> {
    override val supportedSourceClass = PgComposite::class
    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = expectedType.classifier == CharacterManual::class

    override fun convert(
        source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): CharacterManual {
        val stats = source.get<PgComposite>("stats")
        return CharacterManual(
            source.get<Int>("id"),
            source.get<String>("name"),
            StatsManual(stats.get<Int>("strength"), stats.get<Int>("agility"), stats.get<Int>("intelligence"))
        )
    }
}

internal object FlatManualResultConverter : ResultConverter<PgComposite, FlatManual> {
    override val supportedSourceClass = PgComposite::class
    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = expectedType.classifier == FlatManual::class

    override fun convert(
        source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): FlatManual = FlatManual(
        source.get<Int>("id"),
        source.get<String>("name"),
        source.get<Int>("strength"),
        source.get<Int>("agility"),
        source.get<Int>("intelligence")
    )
}

internal class CharacterManualParameterConverter : ParameterConverter<CharacterManual> {
    override val supportedClass = CharacterManual::class

    override fun convert(source: CharacterManual, expectedOid: Int, context: SerializationContext): Any {
        val stats = context.types.containers.createComposite(NESTED_STATS_TYPE)
        stats["strength"] = source.stats.strength
        stats["agility"] = source.stats.agility
        stats["intelligence"] = source.stats.intelligence

        val character = if (expectedOid.isKnownOid) {
            context.types.containers.createComposite(expectedOid)
        } else {
            context.types.containers.createComposite(NESTED_CHARACTER_TYPE)
        }
        character["id"] = source.id
        character["name"] = source.name
        character["stats"] = stats
        return character
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("", NESTED_CHARACTER_TYPE)
}

internal class FlatManualParameterConverter : ParameterConverter<FlatManual> {
    override val supportedClass = FlatManual::class

    override fun convert(source: FlatManual, expectedOid: Int, context: SerializationContext): Any {
        val flat = if (expectedOid.isKnownOid) {
            context.types.containers.createComposite(expectedOid)
        } else {
            context.types.containers.createComposite(FLAT_TYPE)
        }
        flat["id"] = source.id
        flat["name"] = source.name
        flat["strength"] = source.strength
        flat["agility"] = source.agility
        flat["intelligence"] = source.intelligence
        return flat
    }

    override fun getDefaultTypeName(sourceClass: KClass<*>, context: SerializationContext) =
        QualifiedName("", FLAT_TYPE)
}

internal const val NESTED_STATS_TYPE = "bench_cvd_stats"
internal const val NESTED_CHARACTER_TYPE = "bench_cvd_character"
internal const val FLAT_TYPE = "bench_cvd_flat"

/** `strength` is `id % 100`, and this keeps roughly 4% of the table. */
internal const val STRENGTH_THRESHOLD = 95

/**
 * Whether the structure has a level inside it. Declared as a `@Param` so that every rung is measured in both
 * shapes and the two tables can be read against each other.
 */
internal const val SHAPE_NESTED = "nested"
internal const val SHAPE_FLAT = "flat"

/**
 * What `dynamic_dto` costs against the composite it could have been, where the shape is fixed enough that
 * either would do.
 *
 * [The `dynamic_dto` page](../../../../../../../../../docs/client/dynamic-dto.md) says that where the shape is
 * fixed a composite "is cheaper in every direction - no JSON to encode, no discriminator to keep honest". This
 * class is that sentence put to the test, and "every direction" taken literally: reading a column back whole
 * and filtering on it are two different costs that need not agree, and writing is
 * [CompositeVsDynamicDtoWriteBenchmark].
 *
 * ## The four paths
 *
 * `dynamic_dto` is itself a composite - `(type_name text, data_payload jsonb)` - so this is not a composite
 * against something foreign to it. Both arrive as composites, through the same container machinery, and what
 * differs is what sits inside:
 *
 * 1. `compositeAuto` - every attribute through its own codec, the nested one recursively, with the class
 *    matched to the attributes by `registerAutoComposite` on every value.
 * 2. `compositeManual` - the same composite, read by a converter that names the attributes instead of
 *    discovering them. The pair `compositeAuto`/`compositeManual` is what separates the cost of reflection
 *    from the cost of the composite, which one rung alone cannot do.
 * 3. `jsonb` - the same data as one JSON document in a plain `jsonb` column. No discriminator, no envelope.
 * 4. `dynamicDto` - the same document wrapped: a discriminator beside it, and a registry consulted by
 *    `type_name` on the way back to decide which class to decode into. Against rung 3 that difference is the
 *    envelope and nothing else.
 *
 * ## The two shapes
 *
 * `nested` puts `strength`, `agility` and `intelligence` inside a `stats` structure; `flat` puts all five
 * fields side by side. The same rungs run in both, which is what turns "the composite path is slower here"
 * into an answerable question: if its disadvantage is a property of nesting it moves between the two tables,
 * and if it is a property of composites it does not.
 *
 * The shape also changes what the server has to do to filter. Nested, a composite takes two attribute reads
 * and JSON takes two extractions (`-> 'stats' ->> 'strength'`); flat, both take one. So the filter rungs are
 * measured in both for the same reason the read rungs are.
 *
 * ## What is measured
 *
 * **Reading** - 10 000 rows back, decoded into the data class.
 *
 * **Filtering** - the server reaches into the structure and only a few rows come back. This is the direction
 * that has nothing to do with codecs: PostgreSQL reading a composite attribute against PostgreSQL parsing a
 * JSON document and casting text to an integer, once per row scanned. The predicate keeps roughly 4% of the
 * table, so what is timed is the scan and the extraction rather than decoding the result.
 *
 * No index exists on any of the columns, so all of them filter by sequential scan. An expression index is
 * available to every rung and would move every rung; nothing here says what it would do to the gaps.
 *
 * ## What is held still
 *
 * One row carries every encoding of the same character, and the fixture is built by a single SQL statement
 * from `generate_series` rather than written from Kotlin - so the columns of a row hold the same values by
 * construction, and no rung is reading data that another rung's write path produced. The table is seeded once
 * per trial and never written to afterwards.
 *
 * Every rung goes through the client rather than the driver, because `dynamic_dto` registration is the
 * client's API and this keeps them all on one footing. What that adds is the same for all of them, and
 * [ClientOverheadBenchmark] measures it at under a tenth of a percent on an operation of this size.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class CompositeVsDynamicDtoBenchmark {

    @Param(SHAPE_NESTED, SHAPE_FLAT)
    lateinit var shape: String

    private lateinit var pool: HikariDataSource
    private lateinit var client: OctaviusClient

    private lateinit var readAuto: RawQuery
    private lateinit var readManual: RawQuery
    private lateinit var readJsonbQuery: RawQuery
    private lateinit var readDynamic: RawQuery

    private lateinit var filterAuto: RawQuery
    private lateinit var filterJsonbQuery: RawQuery
    private lateinit var filterDynamic: RawQuery

    private val rowCount = 10000

    private val nested: Boolean get() = shape == SHAPE_NESTED

    @Setup(Level.Trial)
    fun setup() {
        Class.forName("io.github.octaviusframework.driver.jdbc.OctaviusDriver")

        val config = HikariConfig()
        config.jdbcUrl = "jdbc:octavius://localhost:5432/octavius_test"
        config.username = "postgres"
        config.password = "1234"
        config.maximumPoolSize = 4
        config.minimumIdle = 4
        config.idleTimeout = 0
        config.maxLifetime = 0

        pool = HikariDataSource(config)
        client = OctaviusClient.fromDataSource(pool)

        // Before the table, which declares a column of the type it creates.
        client.dynamicTypes.install()
        createSchema()

        client.execute {
            reloadTypes()
            if (nested) {
                typeManager.registerAutoComposite<CharacterAuto>(NESTED_CHARACTER_TYPE)
                typeManager.registerAutoComposite<StatsAuto>(NESTED_STATS_TYPE)
            } else {
                typeManager.registerAutoComposite<FlatAuto>(FLAT_TYPE)
            }
        }
        client.dynamicTypes.register<CharacterDynamic>("bench_cvd_character_dyn")
        client.dynamicTypes.register<FlatDynamic>("bench_cvd_flat_dyn")

        prepareQueries()
        seedReadTable()
        verifyEveryPath()
    }

    private fun createSchema() {
        client.rawQuery("DROP TABLE IF EXISTS bench_cvd_read").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_CHARACTER_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_STATS_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $FLAT_TYPE CASCADE").execute()

        if (nested) {
            client.rawQuery("CREATE TYPE $NESTED_STATS_TYPE AS (strength int, agility int, intelligence int)")
                .execute()
            client.rawQuery("CREATE TYPE $NESTED_CHARACTER_TYPE AS (id int, name text, stats $NESTED_STATS_TYPE)")
                .execute()
            client.rawQuery("CREATE TABLE bench_cvd_read (c $NESTED_CHARACTER_TYPE, d public.dynamic_dto, j jsonb)")
                .execute()
        } else {
            client.rawQuery(
                "CREATE TYPE $FLAT_TYPE AS (id int, name text, strength int, agility int, intelligence int)"
            ).execute()
            client.rawQuery("CREATE TABLE bench_cvd_read (c $FLAT_TYPE, d public.dynamic_dto, j jsonb)").execute()
        }
    }

    private fun prepareQueries() {
        readAuto = client.rawQuery("SELECT c FROM bench_cvd_read")
        readManual = client.rawQuery("SELECT c FROM bench_cvd_read")
            .registerResultConverter(if (nested) CharacterManualResultConverter else FlatManualResultConverter)
        readJsonbQuery = client.rawQuery("SELECT j FROM bench_cvd_read")
            .registerResultConverter(if (nested) CharacterJsonResultConverter else FlatJsonResultConverter)
        readDynamic = client.rawQuery("SELECT d FROM bench_cvd_read")

        // Nested, both sides take two steps to reach `strength`; flat, both take one.
        val compositePredicate = if (nested) "((c).stats).strength" else "(c).strength"
        val jsonPath = if (nested) "-> 'stats' ->> 'strength'" else "->> 'strength'"

        filterAuto = client.rawQuery("SELECT c FROM bench_cvd_read WHERE $compositePredicate > @min")
        filterJsonbQuery = client.rawQuery("SELECT j FROM bench_cvd_read WHERE (j $jsonPath)::int > @min")
            .registerResultConverter(if (nested) CharacterJsonResultConverter else FlatJsonResultConverter)
        filterDynamic = client.rawQuery(
            "SELECT d FROM bench_cvd_read WHERE ((d).data_payload $jsonPath)::int > @min"
        )
    }

    /**
     * One row per character carrying every encoding, built entirely by the server.
     *
     * Seeding through the driver's write path would make this fixture depend on the very encoders the write
     * benchmark measures, and would leave the columns equal only if all of those encoders agreed. Building
     * them in one `SELECT` makes them equal by construction, and `dynamic_dto(text, jsonb)` is the function
     * `install()` creates.
     */
    private fun seedReadTable() {
        val sql = if (nested) {
            """
            INSERT INTO bench_cvd_read (c, j, d)
            SELECT ROW(i, 'char_' || i, ROW(i % 100, i % 50, i % 200)::$NESTED_STATS_TYPE)::$NESTED_CHARACTER_TYPE,
                   jsonb_build_object('id', i, 'name', 'char_' || i, 'stats',
                       jsonb_build_object('strength', i % 100, 'agility', i % 50, 'intelligence', i % 200)),
                   dynamic_dto('bench_cvd_character_dyn',
                       jsonb_build_object('id', i, 'name', 'char_' || i, 'stats',
                           jsonb_build_object('strength', i % 100, 'agility', i % 50, 'intelligence', i % 200)))
            FROM generate_series(1, @n) i
            """
        } else {
            """
            INSERT INTO bench_cvd_read (c, j, d)
            SELECT ROW(i, 'char_' || i, i % 100, i % 50, i % 200)::$FLAT_TYPE,
                   jsonb_build_object('id', i, 'name', 'char_' || i,
                       'strength', i % 100, 'agility', i % 50, 'intelligence', i % 200),
                   dynamic_dto('bench_cvd_flat_dyn',
                       jsonb_build_object('id', i, 'name', 'char_' || i,
                           'strength', i % 100, 'agility', i % 50, 'intelligence', i % 200))
            FROM generate_series(1, @n) i
            """
        }
        client.rawQuery(sql).update("n" to rowCount)
    }

    /**
     * Proves each rung is on the path it is named for before any of them is timed.
     *
     * The failure this guards against is silent: a converter that did not claim its value, or a registration
     * the write strategy handed elsewhere, would leave a rung measuring a neighbour and reporting a tie rather
     * than an error.
     */
    private fun verifyEveryPath() {
        val expectedMatches = (1..rowCount).count { it % 100 > STRENGTH_THRESHOLD }
        check(expectedMatches in 1 until rowCount) {
            "the filter has to match some rows and leave others, or it is not a filter"
        }

        val sizes = listOf(readWholeAuto(), readWholeManual(), readWholeJsonb(), readWholeDynamic())
        check(sizes.all { it == rowCount }) { "every path must read back all $rowCount rows, got $sizes" }

        val matches = listOf(filterWithAuto(), filterWithJsonb(), filterWithDynamic())
        check(matches.all { it == expectedMatches }) {
            "the filters must select the same rows: expected $expectedMatches, got $matches"
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        client.rawQuery("DROP TABLE IF EXISTS bench_cvd_read").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_CHARACTER_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $NESTED_STATS_TYPE CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS $FLAT_TYPE CASCADE").execute()
        client.close()
        pool.close()
    }

    // The shape decides which class a row is asked for, and `fetchFields` is reified - so the branch has to be
    // here rather than in a field. It is a predictable test against an operation of several milliseconds.

    private fun readWholeAuto(): Int =
        if (nested) readAuto.fetchFields<CharacterAuto>().size else readAuto.fetchFields<FlatAuto>().size

    private fun readWholeManual(): Int =
        if (nested) readManual.fetchFields<CharacterManual>().size else readManual.fetchFields<FlatManual>().size

    private fun readWholeJsonb(): Int =
        if (nested) readJsonbQuery.fetchFields<CharacterJson>().size
        else readJsonbQuery.fetchFields<FlatJson>().size

    private fun readWholeDynamic(): Int =
        if (nested) readDynamic.fetchFields<CharacterDynamic>().size
        else readDynamic.fetchFields<FlatDynamic>().size

    private fun filterWithAuto(): Int =
        if (nested) filterAuto.fetchFields<CharacterAuto>("min" to STRENGTH_THRESHOLD).size
        else filterAuto.fetchFields<FlatAuto>("min" to STRENGTH_THRESHOLD).size

    private fun filterWithJsonb(): Int =
        if (nested) filterJsonbQuery.fetchFields<CharacterJson>("min" to STRENGTH_THRESHOLD).size
        else filterJsonbQuery.fetchFields<FlatJson>("min" to STRENGTH_THRESHOLD).size

    private fun filterWithDynamic(): Int =
        if (nested) filterDynamic.fetchFields<CharacterDynamic>("min" to STRENGTH_THRESHOLD).size
        else filterDynamic.fetchFields<FlatDynamic>("min" to STRENGTH_THRESHOLD).size

    // --- Reading whole ---------------------------------------------------------------------------------

    @Benchmark
    fun read_compositeAuto(): Int = readWholeAuto()

    @Benchmark
    fun read_compositeManual(): Int = readWholeManual()

    @Benchmark
    fun read_jsonb(): Int = readWholeJsonb()

    @Benchmark
    fun read_dynamicDto(): Int = readWholeDynamic()

    // --- Filtering server-side -------------------------------------------------------------------------

    @Benchmark
    fun filter_composite(): Int = filterWithAuto()

    @Benchmark
    fun filter_jsonb(): Int = filterWithJsonb()

    @Benchmark
    fun filter_dynamicDto(): Int = filterWithDynamic()
}
