package io.github.octaviusframework.driver.composite

import io.github.octaviusframework.driver.type.isKnownOid

import io.github.octaviusframework.driver.converter.parameter.mapper.ParameterConverter
import io.github.octaviusframework.driver.converter.parameter.mapper.SerializationContext
import io.github.octaviusframework.driver.converter.result.mapper.DeserializationContext
import io.github.octaviusframework.driver.converter.result.mapper.ResultConverter
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.type.PgType
import io.github.octaviusframework.driver.container.PgComposite
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.reflect.KType

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualCompositeIntegrationTest {

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

    @BeforeAll
    fun setup() {
        val conn = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
        try {
            conn.createNativeQuery("DROP TABLE IF EXISTS orders CASCADE").execute()
            conn.createNativeQuery("DROP TYPE IF EXISTS payment_info CASCADE").execute()
            conn.createNativeQuery("CREATE TYPE payment_info AS (amount int, currency text)").execute()
            conn.createNativeQuery("CREATE TABLE orders (id int PRIMARY KEY, payment payment_info)").execute()
        } finally {
            conn.close()
        }
    }

    @AfterAll
    fun teardown() {
        val conn = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
        try {
            conn.createNativeQuery("DROP TABLE IF EXISTS orders CASCADE").execute()
            conn.createNativeQuery("DROP TYPE IF EXISTS payment_info CASCADE").execute()
        } finally {
            conn.close()
        }
    }

    @Test
    fun testTransactionWithManualCompositeMapper() {
        val conn = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", "postgres", "1234")
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
                conn.createNamedQuery(insertQuery).update("payment" to payment)

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
}

