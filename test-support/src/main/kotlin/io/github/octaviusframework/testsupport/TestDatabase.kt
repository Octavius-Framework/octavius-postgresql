package io.github.octaviusframework.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.driver.jdbc.getOctaviusSession
import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.registry.GlobalCatalogStore
import io.github.octaviusframework.driver.session.OctaviusSession

/**
 * The database the integration tests run against, and the one place that says where it is.
 *
 * The database belongs to whichever test class is running: [reset] empties it, and forgets the type catalog the
 * driver keeps for it, at the start of every class that extends [AbstractIntegrationTest]. That is sound only one
 * class at a time. JUnit runs the classes of a module one after another, and the
 * `testDatabase` service in the root build keeps the test tasks of two modules from running at once.
 */
object TestDatabase {

    const val HOST = "localhost"
    const val PORT = 5432
    const val DATABASE = "octavius_test"
    const val URL = "jdbc:octavius://$HOST:$PORT/$DATABASE"
    const val USER = "postgres"
    const val PASSWORD = "1234"

    /** Properties for [URL] with the test credentials, and whatever [configure] sets on top of them. */
    fun properties(configure: OctaviusProperties.() -> Unit = {}): OctaviusProperties =
        OctaviusProperties().apply {
            user = USER
            password = PASSWORD
            configure()
        }

    /** A session of its own, on a connection of its own. Whoever opens it closes it. */
    fun openSession(configure: OctaviusProperties.() -> Unit = {}): OctaviusSession =
        getOctaviusSession(URL, properties(configure))

    /** A small pool on [URL], for the tests that reach the database the way an application does. */
    fun dataSource(configure: HikariConfig.() -> Unit = {}): HikariDataSource =
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = URL
            username = USER
            password = PASSWORD
            maximumPoolSize = 2
            configure()
        })

    /**
     * Drops every schema a test could have created, `public` among them, creates an empty `public`, runs [schema],
     * and forgets the database's type catalog - so the next session reads the types [schema] created, and none of
     * the registrations an earlier class made.
     *
     * A class that wants a schema of its own creates it in [schema] and leaves it; the next class's reset takes
     * it away with everything else.
     *
     * The drops wait at most ten seconds for a lock. A transaction an earlier test left open on a table would
     * otherwise hang them with nothing said, and a failure naming the lock is the quicker way to that test.
     *
     * @param schema The DDL to run once the database is empty, as one script.
     */
    fun reset(schema: String? = null) {
        openSession().use { session ->
            session.createNativeQuery(RESET).execute()
            if (schema != null) session.createNativeQuery(schema).execute()
        }
        GlobalCatalogStore.removeCatalog(URL)
    }

    private const val RESET = $$"""
        SET lock_timeout = '10s';
        DO $reset$
        DECLARE
            name text;
        BEGIN
            FOR name IN
                SELECT nspname FROM pg_namespace WHERE nspname <> 'information_schema' AND nspname NOT LIKE 'pg\_%'
            LOOP
                EXECUTE format('DROP SCHEMA %I CASCADE', name);
            END LOOP;
        END
        $reset$;
        CREATE SCHEMA public;
    """
}
