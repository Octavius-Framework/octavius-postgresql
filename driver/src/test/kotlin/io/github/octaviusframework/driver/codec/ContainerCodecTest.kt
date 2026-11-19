package io.github.octaviusframework.driver.codec

import io.github.octaviusframework.driver.container.ArrayDimension
import io.github.octaviusframework.driver.container.PgArray
import io.github.octaviusframework.driver.container.PgComposite
import io.github.octaviusframework.driver.container.PgContainer
import io.github.octaviusframework.driver.io.PgByteWriter
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.registry.DatabaseKey
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * A container built on this side encodes to the same bytes as the container PostgreSQL builds for the same value,
 * both going through the codec the catalog binds to the container's OID.
 */
class ContainerCodecTest : AbstractIntegrationTest() {

    override val schema = "CREATE TYPE legion_standard AS (id int, name text)"

    private fun encode(container: PgContainer): ByteArray {
        val catalog = GlobalCatalogStore.holderFor(DatabaseKey.from(OctaviusProperties.parse(TestDatabase.URL))).catalog
        val codec = catalog.codecs.getCodecByOid<PgContainer>(container.containerOid)
        assertNotNull(codec, "no codec for OID ${container.containerOid}")
        return PgByteWriter().also { codec.encodeSafely(container, it) }.toByteArray()
    }

    private fun OctaviusSession.serverBuilt(sql: String): PgContainer =
        createNativeQuery(sql).fetchRowStrict().get<PgContainer>(0)

    @Test
    fun `a composite built through the factory encodes as the one the server built`() {
        openSession().use { session ->
            val built = session.typeManager.containers.createComposite("legion_standard")
            built["id"] = 777
            built["name"] = "Legio X Equestris"

            val server = session.serverBuilt("SELECT ROW(777, 'Legio X Equestris')::legion_standard")

            assertContentEquals(encode(server as PgComposite), encode(built))
        }
    }

    @Test
    fun `an array built by hand encodes as the one the server built, in one dimension or two`() {
        openSession().use { session ->
            val flat = PgArray(
                arrayOid = 1007,
                elementOid = 23,
                dimensions = listOf(ArrayDimension(3, 1)),
                elements = mutableListOf(10, 20, 30)
            )
            assertContentEquals(encode(session.serverBuilt("SELECT ARRAY[10, 20, 30]::int[]")), encode(flat))

            val grid = PgArray(
                arrayOid = 1007,
                elementOid = 23,
                dimensions = listOf(ArrayDimension(2, 1), ArrayDimension(3, 1)),
                elements = mutableListOf(1, 2, 3, 4, 5, 6)
            )
            assertContentEquals(encode(session.serverBuilt("SELECT ARRAY[[1, 2, 3], [4, 5, 6]]::int[]")), encode(grid))
        }
    }
}
