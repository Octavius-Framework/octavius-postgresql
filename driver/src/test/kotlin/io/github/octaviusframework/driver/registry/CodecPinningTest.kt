package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.type.PgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

/**
 * What a container codec resolves its nested values through.
 *
 * A codec for a composite reads the attribute names and the codec for each field out of a pair of dictionaries.
 * The guarantee under test is that the pair is the one the codec was built for rather than whatever the registry
 * publishes at the moment the bytes arrive - which is what keeps a result that is still being read from being
 * decoded against a catalog that replaced the one it was described by.
 */
class CodecPinningTest {

    private val compositeOid = 90001
    private val int4Oid = 23

    private fun catalogTypes(attributeName: String): Map<Int, PgType> = mapOf(
        compositeOid to PgType.Composite(
            compositeOid, "pinning_t", "public", linkedMapOf(attributeName to int4Oid)
        )
    )

    /** One composite on the wire: field count, then the OID, length and payload of its single attribute. */
    private fun compositeBytes(value: Int): ByteArray =
        ByteBuffer.allocate(16).putInt(1).putInt(int4Oid).putInt(4).putInt(value).array()

    @Test
    fun `a codec decodes through the catalog it was built for, not the one published since`() {
        val registry = CatalogHolder()

        registry.update { it.withTypes(catalogTypes("before")) }
        val firstCodec = registry.catalog.codecs.getCodecByOid<PgComposite>(compositeOid)!!

        registry.update { it.withTypes(catalogTypes("after")) }
        val secondCodec = registry.catalog.codecs.getCodecByOid<PgComposite>(compositeOid)!!

        // A generation of its own per catalog is what makes the binding sound: a codec kept from the previous
        // dictionary would have been bound twice, and the first binding would have moved under the first catalog.
        assertNotSame(firstCodec, secondCodec, "each catalog builds its own dynamic codecs")

        val bytes = compositeBytes(7)
        val fromFirst = firstCodec.fromBinary(bytes, 0, bytes.size)
        val fromSecond = secondCodec.fromBinary(bytes, 0, bytes.size)

        assertEquals(listOf("before"), fromFirst.type.attributes.keys.toList())
        assertEquals(listOf("after"), fromSecond.type.attributes.keys.toList())
        assertEquals(7, fromFirst.get<Int>("before"))
        assertEquals(7, fromSecond.get<Int>("after"))
    }
}
