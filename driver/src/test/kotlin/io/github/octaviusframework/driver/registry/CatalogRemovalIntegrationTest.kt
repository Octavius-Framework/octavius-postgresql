package io.github.octaviusframework.driver.registry

import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A catalog dropped by [GlobalCatalogStore.removeCatalog] while a connection to its database was still open.
 */
class CatalogRemovalIntegrationTest : AbstractIntegrationTest() {

    enum class Province { Asia, Africa }

    @AfterEach
    fun dropTheType() {
        openSession().use { it.createNativeQuery("DROP TYPE IF EXISTS province").execute() }
        GlobalCatalogStore.removeCatalog(TestDatabase.URL)
    }

    @Test
    fun `a reload reaches the session whose catalog was dropped while it was open`() {
        openSession().use { session ->
            GlobalCatalogStore.removeCatalog(TestDatabase.URL)
            session.createNativeQuery("CREATE TYPE province AS ENUM ('ASIA', 'AFRICA')").execute()

            session.reloadTypes()
            session.typeManager.registerEnum<Province>()

            assertEquals(Province.Africa, session.createNativeQuery("SELECT 'AFRICA'::province").fetchField<Province>())
        }
    }
}
