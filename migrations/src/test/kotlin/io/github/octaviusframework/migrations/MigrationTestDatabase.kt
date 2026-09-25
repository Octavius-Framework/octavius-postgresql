package io.github.octaviusframework.migrations

import io.github.octaviusframework.driver.jdbc.OctaviusDataSource
import io.github.octaviusframework.driver.session.OctaviusSession
import io.github.octaviusframework.testsupport.TestDatabase

/**
 * The database the integration tests here run against, and the schema they are allowed to make a mess of.
 *
 * Every test opens a session of its own. Sharing one would make a single failed transaction knock over every
 * test after it, and a suite where one fault reads as ten proves nothing about nine of them.
 */
internal object MigrationTestDatabase {

    const val SCHEMA = "octavius_migrations_test"

    fun session(): OctaviusSession = TestDatabase.openSession()

    /** Wipes the test schema and puts an empty one back. */
    fun reset() {
        session().use { session ->
            session.createNativeQuery(
                """
                DROP SCHEMA IF EXISTS "$SCHEMA" CASCADE;
                CREATE SCHEMA "$SCHEMA";
                """.trimIndent()
            ).execute()
        }
    }

    /** The same database behind a `DataSource`, which is how the migrator is meant to be reached. */
    fun dataSource(): OctaviusDataSource = OctaviusDataSource().apply {
        url = TestDatabase.URL
        user = TestDatabase.USER
        password = TestDatabase.PASSWORD
    }

    fun drop() {
        session().use { it.createNativeQuery("""DROP SCHEMA IF EXISTS "$SCHEMA" CASCADE""").execute() }
    }
}
