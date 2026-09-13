package io.github.octaviusframework.driver.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.client.OctaviusClient
import io.github.octaviusframework.client.query.RawQuery
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType

data class WalkStats(val strength: Int, val agility: Int, val intelligence: Int)
data class WalkCharacter(val id: Int, val name: String, val stats: WalkStats)

/**
 * A converter that is offered every value and claims none, declining without touching [KType.classifier].
 *
 * `Any::class` as the source class is what puts it in front of every conversion rather than one kind, which is
 * the position the registry's own converters occupy.
 */
private class DecliningCheap : ResultConverter<Any, Any> {
    override val supportedSourceClass = Any::class

    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean = sourceClass == Nothing::class

    override fun convert(
        source: Any, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Any = error("this converter never claims a value")
}

/**
 * The same, declining on the same values, but reading [KType.classifier] first.
 *
 * That property is the one a converter reaches for when it wants to know what it is being asked to produce,
 * and it is backed by kotlin-reflect rather than by a field. Everything else about this class is identical to
 * [DecliningCheap], so the distance between the two is the cost of that one read and nothing else.
 */
private class DecliningViaClassifier : ResultConverter<Any, Any> {
    override val supportedSourceClass = Any::class

    override fun canConvert(
        sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Boolean {
        val kClass = expectedType.classifier as? KClass<*> ?: return false
        return kClass == Nothing::class
    }

    override fun convert(
        source: Any, expectedType: KType, sourceType: PgType, context: DeserializationContext
    ): Any = error("this converter never claims a value")
}

/**
 * What the converter registry costs to walk, per value, and how much of that is `expectedType.classifier`.
 *
 * Every value the driver decodes is offered to the converters registered ahead of the built-in path, one at a
 * time, until one claims it. That walk is per value rather than per row or per query, so a composite of five
 * fields with a structure inside it pays for it seven times a row — which is why this benchmark reads one.
 *
 * Two things are varied, and nothing else:
 *
 * - **`converters`** — how many declining converters sit in front. The slope across this is what one more
 *   registered converter costs, and it is the answer to whether adding a few hand-written ones slows down
 *   everything that does not use them.
 * - **`kind`** — whether each of them reads [KType.classifier] before declining. At the same count, the
 *   distance between `cheap` and `classifier` is the price of that single property access, multiplied by the
 *   converters and by the values.
 *
 * At `converters = 0` the two kinds are the same code with nothing registered, so those two rows should land
 * on top of each other. They are left in as a control: if they do not, the rest of the table is not reading
 * what it claims to.
 *
 * ## What the answer is for
 *
 * `ResultConverter.canConvert` receives `sourceClass` already resolved but `expectedType` as a `KType`, so a
 * converter that needs the expected class resolves it itself — and every converter in the walk resolves it
 * again, for the same value. Whether that asymmetry is worth correcting in the signature depends on what one
 * such resolution costs and how many of them a real registry performs; this measures the first and lets the
 * second be counted.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(3)
@Threads(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
open class ConverterWalkBenchmark {

    @Param("0", "1", "5", "20")
    var converters: Int = 0

    @Param("cheap", "classifier")
    lateinit var kind: String

    private lateinit var pool: HikariDataSource
    private lateinit var client: OctaviusClient
    private lateinit var read: RawQuery

    private val rowCount = 10000

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

        client.rawQuery("DROP TABLE IF EXISTS bench_walk").execute()
        client.rawQuery("DROP TYPE IF EXISTS bench_walk_character CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS bench_walk_stats CASCADE").execute()
        client.rawQuery("CREATE TYPE bench_walk_stats AS (strength int, agility int, intelligence int)").execute()
        client.rawQuery("CREATE TYPE bench_walk_character AS (id int, name text, stats bench_walk_stats)").execute()
        client.rawQuery("CREATE TABLE bench_walk (c bench_walk_character)").execute()
        client.rawQuery(
            """
            INSERT INTO bench_walk (c)
            SELECT ROW(i, 'char_' || i, ROW(i % 100, i % 50, i % 200)::bench_walk_stats)::bench_walk_character
            FROM generate_series(1, @n) i
            """
        ).update("n" to rowCount)

        client.execute {
            reloadTypes()
            typeManager.registerAutoComposite<WalkCharacter>("bench_walk_character")
            typeManager.registerAutoComposite<WalkStats>("bench_walk_stats")

            // In front of everything, which is where a registration goes and where the walk starts.
            repeat(converters) {
                typeManager.registerResultConverter(
                    if (kind == "classifier") DecliningViaClassifier() else DecliningCheap()
                )
            }
        }

        read = client.rawQuery("SELECT c FROM bench_walk")

        val rows = read.fetchFields<WalkCharacter>()
        check(rows.size == rowCount) { "the read must return all $rowCount rows, got ${rows.size}" }
        check(rows.first().stats.strength == 1) { "the nested attribute must survive the walk" }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        client.rawQuery("DROP TABLE IF EXISTS bench_walk").execute()
        client.rawQuery("DROP TYPE IF EXISTS bench_walk_character CASCADE").execute()
        client.rawQuery("DROP TYPE IF EXISTS bench_walk_stats CASCADE").execute()
        client.close()
        pool.close()
    }

    @Benchmark
    fun read_nestedComposite(): Int = read.fetchFields<WalkCharacter>().size
}
