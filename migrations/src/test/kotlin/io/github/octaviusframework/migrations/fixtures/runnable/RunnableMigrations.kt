package io.github.octaviusframework.migrations.fixtures.runnable

import io.github.octaviusframework.driver.session.OctaviusSessionOperations
import io.github.octaviusframework.migrations.MigrationTestDatabase
import io.github.octaviusframework.migrations.OctaviusMigration

/** A code migration that actually does something, for the end-to-end test. */
class V20__Create_from_code : OctaviusMigration {
    override fun migrate(session: OctaviusSessionOperations) {
        session.createNativeQuery("CREATE TABLE ${MigrationTestDatabase.SCHEMA}.from_code (id int)").execute()
    }
}

/** The same, without a transaction - the path where the run records RUNNING first. */
class V21__Fill_from_code : OctaviusMigration {
    override val transactional: Boolean get() = false

    override fun migrate(session: OctaviusSessionOperations) {
        session.createNativeQuery("INSERT INTO ${MigrationTestDatabase.SCHEMA}.from_code VALUES (1)").executeUpdate()
    }
}
