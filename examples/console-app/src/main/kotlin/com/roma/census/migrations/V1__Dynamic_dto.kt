package com.roma.census.migrations

import io.github.octaviusframework.client.dynamic.DYNAMIC_DTO_DDL
import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.migrations.OctaviusMigration

/** Creates the `dynamic_dto` type the `notae` table in V2 is declared with. */
class V1__Dynamic_dto : OctaviusMigration {
    override fun migrate(session: OctaviusSessionOperations) {
        session.createNativeQuery(DYNAMIC_DTO_DDL).execute()
    }
}
