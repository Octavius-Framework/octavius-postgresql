package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

data class Circle(val info: String)

class CircleCodec : TypeCodec<Circle> {
    override val pgTypeName: String = "circle"
    override val oid: Int? = null // Explicitly null
    override val kotlinClass: KClass<Circle> = Circle::class

    override val fromBinary: (ByteArray, Int, Int) -> Circle = { _, _, _ ->
        Circle("dummy_circle")
    }
    
    override val toBinary: (Circle, PgByteWriter) -> Unit = { _, _ ->
        // dummy impl
    }
}

class CodecRegistrationTest {

    @Test
    fun `should register codec without oid by resolving it from database`() {
        val props = OctaviusProperties()
        props.user = "postgres"
        props.password = "1234"

        val session = getOctaviusSession("jdbc:octavius://localhost:5432/octavius_test", props)
        
        val codec = CircleCodec()
        session.typeManager.registerCodec(codec)
        
        val oid = session.typeManager.resolveOid("circle")
        
        val retrievedCodec = session.typeManager.codecs.getCodecByOid<Circle>(oid)
        
        assertNotNull(retrievedCodec, "Codec should be registered and retrievable by resolved OID")
        assertEquals(Circle::class, retrievedCodec?.kotlinClass)

        // Verify that the codec is used during query execution
        val row = session.createNativeQuery("SELECT '<(1,2),3>'::circle as circle_test").fetchRowStrict()
        val result = row.get<Circle>("circle_test")
        
        assertNotNull(result)
        assertEquals("dummy_circle", result.info)
        
        session.close()
    }
}
