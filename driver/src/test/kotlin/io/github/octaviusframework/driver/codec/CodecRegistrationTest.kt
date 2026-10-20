package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass

/** A round shield, which is all a `circle` has to be to this test. */
data class Clipeus(val info: String)

class ClipeusCodec : TypeCodec<Clipeus> {
    override val pgTypeName: String = "circle"
    override val oid: Int? = null // Explicitly null
    override val kotlinClass: KClass<Clipeus> = Clipeus::class
    override val isDefaultForKotlinType: Boolean = true

    override val fromBinary: (ByteArray, Int, Int) -> Clipeus = { _, _, _ ->
        Clipeus("umbo")
    }

    // Every shield goes out as the unit circle: what the tests write one for is the type it is declared as
    override val toBinary: (Clipeus, PgByteWriter) -> Unit = { _, writer ->
        writer.writeDouble(0.0)
        writer.writeDouble(0.0)
        writer.writeDouble(1.0)
    }
}

class CodecRegistrationTest : AbstractIntegrationTest() {

    @Test
    fun `should register codec without oid by resolving it from database`() {
        val session = openSession()
        
        val codec = ClipeusCodec()
        session.typeManager.registerCodec(codec)
        
        val oid = session.typeManager.resolveOid("circle")
        
        val retrievedCodec = session.typeManager.codecs.getCodecByOid<Clipeus>(oid)
        
        assertNotNull(retrievedCodec, "Codec should be registered and retrievable by resolved OID")
        assertEquals(Clipeus::class, retrievedCodec?.kotlinClass)

        // Verify that the codec is used during query execution
        val row = session.createNativeQuery("SELECT '<(1,2),3>'::circle as shield").fetchRowStrict()
        val result = row.get<Clipeus>("shield")
        
        assertNotNull(result)
        assertEquals("umbo", result.info)

        session.close()
    }

    @Test
    fun `a value of a codec registered by type name is declared as that type, in a list too`() {
        openSession().use { session ->
            session.typeManager.registerCodec(ClipeusCodec())

            fun declared(value: Any): String =
                session.createNativeQuery("SELECT pg_typeof($1)::text").fetchRowStrict(value).get(0)

            assertEquals("circle", declared(Clipeus("umbo")))
            assertEquals("circle[]", declared(listOf(Clipeus("umbo"), Clipeus("umbo"))))
        }
    }

    @Test
    fun `a codec declaring OID 0 is refused, and nothing is bound to 0`() {
        openSession().use { session ->
            val boundToNothing = object : TypeCodec<Clipeus> by ClipeusCodec() {
                override val oid: Int? = 0
            }

            val e = assertThrows<InvalidOperationException> { session.typeManager.registerCodec(boundToNothing) }

            assertEquals(InvalidOperationExceptionReason.INVALID_ARGUMENT, e.reason)
            assertNull(session.typeManager.codecs.getCodecByOid<Clipeus>(0))
        }
    }
}
