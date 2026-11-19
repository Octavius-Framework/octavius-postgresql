package io.github.octaviusframework.driver.converter

import io.github.octaviusframework.driver.type.isKnownOid

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class ManualCompositeTest : AbstractIntegrationTest() {

    data class PaymentInfo(val amount: Int, val currency: String)

    class PaymentInfoResultConverter : ResultConverter<PgComposite, PaymentInfo> {
        override val supportedSourceClass = PgComposite::class
        override fun canConvert(sourceClass: kotlin.reflect.KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext): Boolean {
            return expectedType.classifier == PaymentInfo::class && sourceType is PgType.Composite && sourceType.name == "payment_info"
        }

        override fun convert(
            source: PgComposite,
            expectedType: KType,
            sourceType: PgType,
            context: DeserializationContext
        ): PaymentInfo {
            val amount = source.get<Int>("amount")
            val currency = source.get<String>("currency")
            return PaymentInfo(amount, currency)
        }
    }

    class PaymentInfoParameterConverter : ParameterConverter<PaymentInfo> {
        override val supportedClass = PaymentInfo::class

        override fun convert(source: PaymentInfo, expectedOid: Int, context: SerializationContext): Any {
            // Building the composite is much cleaner through the TypeManager
            val composite = if (expectedOid.isKnownOid) {
                context.types.containers.createComposite(expectedOid)
            } else {
                context.types.containers.createComposite("payment_info")
            }
            
            // Attributes are addressed by name
            composite["amount"] = source.amount
            composite["currency"] = source.currency
            
            return composite
        }
    }

    override val schema = """
        CREATE TYPE payment_info AS (amount int, currency text);
        CREATE TABLE orders (id int PRIMARY KEY, payment payment_info);

        CREATE TYPE allegiance AS ENUM ('LOYAL', 'REBEL', 'UNKNOWN');
        CREATE TYPE client_king AS (realm text, allegiance allegiance);
        CREATE TYPE frontier AS (standing allegiance, king client_king);
    """.trimIndent()

    @Test
    fun `hand-written converters write a composite into a table and read it back`() {
        val conn = openSession()
        try {
            // Force a fresh type load, payment_info included
            conn.reloadTypes()
            
            // Register the hand-written mappers
            conn.typeManager.registerResultConverter(PaymentInfoResultConverter())
            conn.typeManager.registerParameterConverter(PaymentInfoParameterConverter())

            conn.transaction.required {
                val payment = PaymentInfo(1500, "PLN")
                
                // Insert through a NamedQuery, cast to payment_info
                val insertQuery = "INSERT INTO orders (id, payment) VALUES (1, @payment)"
                conn.createNamedQuery(insertQuery).executeUpdate("payment" to payment)

                // Read it back inside the transaction
                val selectQuery = "SELECT payment FROM orders WHERE id = 1"
                val resultRow = conn.createNativeQuery(selectQuery).fetchRowStrict()
                assertNotNull(resultRow)

                val fetchedPayment = resultRow.get<PaymentInfo>("payment")
                assertEquals(1500, fetchedPayment.amount)
                assertEquals("PLN", fetchedPayment.currency)
            }
            
            // Check again outside the transaction
            val countRows = conn.createNativeQuery("SELECT COUNT(*) FROM orders").fetchRowStrict().get<Long>(0)
            assertEquals(1L, countRows)

        } finally {
            conn.close()
        }
    }

    enum class Allegiance { LOYAL, REBEL, UNKNOWN }
    data class ClientKing(val realm: String, val allegiance: Allegiance)

    @Test
    fun `hand-written converters apply inside a composite read as a map, nesting through the context`() {
        openSession().use { session ->
            session.typeManager.registerResultConverter(object : ResultConverter<Any, Allegiance> {
                override val supportedSourceClass = Any::class
                override fun canConvert(
                    sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
                ) = expectedType.classifier == Allegiance::class || sourceType.name == "allegiance"

                override fun convert(
                    source: Any, expectedType: KType, sourceType: PgType, context: DeserializationContext
                ) = Allegiance.entries.find { it.name.equals(source.toString(), ignoreCase = true) }
                    ?: Allegiance.UNKNOWN
            })

            session.typeManager.registerResultConverter(object : ResultConverter<PgComposite, ClientKing> {
                override val supportedSourceClass = PgComposite::class
                override fun canConvert(
                    sourceClass: KClass<*>, expectedType: KType, sourceType: PgType, context: DeserializationContext
                ) = expectedType.classifier == ClientKing::class || sourceType.name == "client_king"

                override fun convert(
                    source: PgComposite, expectedType: KType, sourceType: PgType, context: DeserializationContext
                ) = ClientKing(
                    realm = source.get("realm"),
                    allegiance = context.convert(
                        source.get<Any>("allegiance"), typeOf<Allegiance>(), source.getAttributeOid("allegiance")
                    )
                )
            })

            val frontier = session.createNativeQuery(
                "SELECT ROW('LOYAL'::allegiance, ROW('Armenia', 'REBEL')::client_king)::frontier"
            ).fetchFieldStrict<Map<String, Any?>>()

            assertEquals(
                mapOf("standing" to Allegiance.LOYAL, "king" to ClientKing("Armenia", Allegiance.REBEL)),
                frontier
            )
        }
    }
}

