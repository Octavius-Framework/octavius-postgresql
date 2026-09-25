package io.github.octaviusframework.client

import com.zaxxer.hikari.HikariDataSource
import io.github.octaviusframework.testsupport.AbstractIntegrationTest
import io.github.octaviusframework.testsupport.TestDatabase
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

/**
 * A client test class: an empty database with [schema] in it, and one client over one pool for the whole class.
 *
 * The pool is opened after the database has been emptied, so the first connection reads the types [schema]
 * created. `dynamic_dto` is not installed: a class that needs it puts
 * [DYNAMIC_DTO_DDL][io.github.octaviusframework.client.dynamic.DYNAMIC_DTO_DDL] in its [schema], or calls
 * `install()` where installing is what it tests.
 */
abstract class AbstractClientIntegrationTest : AbstractIntegrationTest() {

    /** Two by default, so that `REQUIRES_NEW` has a second connection to take. */
    protected open val poolSize: Int = 2

    protected lateinit var dataSource: HikariDataSource
    protected lateinit var db: OctaviusClient

    @BeforeAll
    fun openClient() {
        dataSource = TestDatabase.dataSource { maximumPoolSize = poolSize }
        db = OctaviusClient.fromDataSource(dataSource)
    }

    @AfterAll
    fun closeClient() {
        db.close()
        dataSource.close()
    }
}
