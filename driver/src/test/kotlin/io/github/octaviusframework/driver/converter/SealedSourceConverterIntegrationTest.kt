package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.row.Row
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.type.datetime.PgInterval
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * A result converter registered under a sealed class is offered every class below it, the codec having decoded
 * to one of those: `interval` arrives as `PgInterval.Finite` or one of the two infinities, never as `PgInterval`.
 */
class SealedSourceConverterIntegrationTest : AbstractIntegrationTest() {

    // One target per test, since what a test registers on the session stays for the rest of the class
    data class Term(val text: String)
    data class Tenure(val text: String)
    data class Lustrum(val text: String)

    private class TagConverter<S : Any, T : Any>(
        override val supportedSourceClass: KClass<S>,
        private val target: KClass<T>,
        private val tag: String,
        private val make: (String) -> T
    ) : ResultConverter<S, T> {
        override fun canConvert(sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            expectedType.classifier == target

        override fun convert(source: S, expectedType: KType, sourceType: PgType, context: DeserializationContext) =
            make("$tag:${source::class.simpleName}")
    }

    private fun OctaviusSession.terms(): Row =
        createNativeQuery("SELECT '1 day'::interval AS finite, 'infinity'::interval AS inf, '-infinity'::interval AS minus_inf")
            .fetchRowStrict()

    @Test
    fun `a converter registered under PgInterval is offered the finite interval and both infinities`() {
        openSession().use { session ->
            session.typeManager.registerResultConverter(TagConverter(PgInterval::class, Term::class, "sealed", ::Term))

            val row = session.terms()

            assertEquals(Term("sealed:Finite"), row.get<Term>("finite"))
            assertEquals(Term("sealed:Infinity"), row.get<Term>("inf"))
            assertEquals(Term("sealed:MinusInfinity"), row.get<Term>("minus_inf"))
        }
    }

    @Test
    fun `under a sealed class and under a class below it, the later registration is asked first`() {
        openSession().use { session ->
            val types = session.typeManager
            types.registerResultConverter(TagConverter(PgInterval.Finite::class, Tenure::class, "finite", ::Tenure))
            types.registerResultConverter(TagConverter(PgInterval::class, Tenure::class, "sealed", ::Tenure))

            assertEquals(Tenure("sealed:Finite"), session.terms().get<Tenure>("finite"))

            types.registerResultConverter(TagConverter(PgInterval.Finite::class, Tenure::class, "later", ::Tenure))

            val row = session.terms()
            assertEquals(Tenure("later:Finite"), row.get<Tenure>("finite"))
            assertEquals(Tenure("sealed:Infinity"), row.get<Tenure>("inf"))
        }
    }

    @Test
    fun `a query's own converter under PgInterval is offered every class below it, newest first`() {
        openSession().use { session ->
            val row = session.createNativeQuery("SELECT '1 day'::interval AS finite, 'infinity'::interval AS inf")
                .registerResultConverter(TagConverter(PgInterval.Finite::class, Lustrum::class, "finite", ::Lustrum))
                .registerResultConverter(TagConverter(PgInterval::class, Lustrum::class, "sealed", ::Lustrum))
                .registerResultConverter(TagConverter(PgInterval.Infinity::class, Lustrum::class, "infinity", ::Lustrum))
                .fetchRowStrict()

            assertEquals(Lustrum("sealed:Finite"), row.get<Lustrum>("finite"))
            assertEquals(Lustrum("infinity:Infinity"), row.get<Lustrum>("inf"))
        }
    }
}
