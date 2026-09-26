package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/** A round shield, which is all a `circle` has to be to this test. */
data class Clipeus(val info: String)

class ClipeusCodec : TypeCodec<Clipeus> {
    override val pgTypeName: String = "circle"
    override val oid: Int? = null // Explicitly null
    override val kotlinClass: KClass<Clipeus> = Clipeus::class

    override val fromBinary: (ByteArray, Int, Int) -> Clipeus = { _, _, _ ->
        Clipeus("umbo")
    }
    
    override val toBinary: (Clipeus, PgByteWriter) -> Unit = { _, _ ->
        // never written: the test only reads one
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
}
