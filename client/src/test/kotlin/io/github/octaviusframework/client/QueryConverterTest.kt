package io.github.octaviusframework.client

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.exception.OctaviusException
import io.github.octaviusframework.driver.type.PgType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Converters registered on one query: that they apply to it, and that nothing else on the connection sees them.
 *
 * The second half is the point. The driver gives every query registries of its own chained to the session's,
 * so a one-off mapping is possible without touching the type manager - which is global to the database and
 * would reach every session pointing at it. What these tests pin is that the client's builders reach that, and
 * reach only that.
 */
class QueryConverterTest : AbstractClientIntegrationTest() {

    /** Reads an `int4` as a `String`, which nothing does by default - so its effect is unmistakable. */
    private object TaggedIntConverter : ResultConverter<Int, String> {
        override val supportedSourceClass: KClass<Int> = Int::class

        override fun canConvert(
            sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
        ): Boolean = expectedType.classifier == String::class

        override fun convert(
            source: Int, expectedType: KType, sourceType: PgType, context: DeserializationContext
        ): String = "N=$source"
    }

    /** Sends a `Rank` as the text of its ordinal, which again nothing would do on its own. */
    private object RankConverter : ParameterConverter<Rank> {
        override val supportedClass: KClass<Rank> = Rank::class

        override fun convert(source: Rank, expectedOid: Int, context: SerializationContext): Any =
            "rank-${source.ordinal}"
    }

    enum class Rank { Legatus, Tribunus }

    override val poolSize = 1 // one connection, so a leaked converter would certainly be seen

    override val schema = "CREATE TABLE qc_probe (id SERIAL PRIMARY KEY, amount INT, label TEXT)"

    @BeforeEach
    fun seed() {
        db.rawQuery("TRUNCATE qc_probe RESTART IDENTITY").execute()
        db.insertInto("qc_probe").values(listOf("amount")).update("amount" to 300)
    }

    @Test
    fun `a result converter registered on a query applies to it`() {
        val out = db.select("amount").from("qc_probe")
            .registerResultConverter(TaggedIntConverter)
            .fetchFieldStrict<String>()

        assertEquals("N=300", out)
    }

    @Test
    fun `and to no other query on the same connection`() {
        db.select("amount").from("qc_probe").registerResultConverter(TaggedIntConverter)
            .fetchFieldStrict<String>()

        // The pool holds one connection, so this is the very session the converter was used on. Asking the
        // same column for a String again has to fail, the registry it was added to having gone with the query.
        assertFailsWith<OctaviusException> {
            db.select("amount").from("qc_probe").fetchFieldStrict<String>()
        }
    }

    @Test
    fun `it can sit anywhere in the chain, the builder's type surviving it`() {
        // What the generic receiver buys: registering mid-chain still hands back a SelectQuery, so `where` and
        // `orderBy` are still there afterwards.
        val out = db.select("amount").from("qc_probe")
            .registerResultConverter(TaggedIntConverter)
            .where("id = @id")
            .orderBy("id")
            .fetchFieldStrict<String>("id" to 1)

        assertEquals("N=300", out)
    }

    @Test
    fun `copy carries the converters a builder was given`() {
        val base = db.select("amount").from("qc_probe").registerResultConverter(TaggedIntConverter)

        assertEquals("N=300", base.copy().where("id = @id").fetchFieldStrict<String>("id" to 1))
    }

    @Test
    fun `a parameter converter registered on a query applies to it`() {
        db.insertInto("qc_probe").values(listOf("label"))
            .registerParameterConverter(RankConverter)
            .update("label" to Rank.Tribunus)

        assertEquals(
            "rank-1",
            db.select("label").from("qc_probe").where("label IS NOT NULL").fetchFieldStrict<String>()
        )
    }

    @Test
    fun `and a later query has to say so again`() {
        db.insertInto("qc_probe").values(listOf("label"))
            .registerParameterConverter(RankConverter)
            .update("label" to Rank.Tribunus)

        assertFailsWith<OctaviusException> {
            db.insertInto("qc_probe").values(listOf("label")).update("label" to Rank.Legatus)
        }
    }
}
