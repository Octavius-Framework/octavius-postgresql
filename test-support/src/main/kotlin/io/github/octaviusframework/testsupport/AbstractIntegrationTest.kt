package io.github.octaviusframework.testsupport

import io.github.octaviusframework.driver.properties.OctaviusProperties
import io.github.octaviusframework.driver.session.OctaviusSession
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * A test class that starts from an empty database and a driver that has registered nothing.
 *
 * Before any test in the class runs, [TestDatabase.reset] drops every schema and runs [schema] in a fresh
 * `public`, so a class neither sees what the one before it left behind nor has anything to clean up after itself.
 * Setup that is not DDL - registering types, inserting rows - goes in a `@BeforeAll` of the class's own, which
 * JUnit runs after this one.
 *
 * The lifecycle is per class, so that this `@BeforeAll` and the subclass's can both be instance methods.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractIntegrationTest {

    /** The types, tables and routines this class needs, created before any of its tests run. */
    protected open val schema: String? = null

    @BeforeAll
    fun startFromEmptyDatabase() {
        TestDatabase.reset(schema)
    }

    /** A session of its own on the test database. Whoever opens it closes it. */
    protected fun openSession(configure: OctaviusProperties.() -> Unit = {}): OctaviusSession =
        TestDatabase.openSession(configure)
}
