package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import java.lang.reflect.Proxy
import java.sql.DatabaseMetaData

/**
 * The two answers about the database that the managers in front of the driver ask for, and a refusal for every
 * other question - `InvalidOperationException(FEATURE_NOT_SUPPORTED)`, as `getMetaData()` itself gave before.
 *
 * - [supportsSavepoints]: Spring's transaction manager asks before it takes the savepoint for `Propagation.NESTED`,
 *   and refuses to take one without a yes.
 * - [getDatabaseProductName]: Spring Boot's `db` health indicator labels its report with it before it checks the
 *   connection with `isValid()`, and reports `DOWN` where the label cannot be had.
 *
 * Everything else stays refused: a tool that reads more of this to decide how to talk to the database would go on
 * to run its SQL through `PreparedStatement` and `ResultSet`, which the driver does not implement either.
 */
internal object OctaviusDatabaseMetaData : DatabaseMetaData by refusal {
    override fun supportsSavepoints(): Boolean = true

    override fun getDatabaseProductName(): String = "PostgreSQL"

    // Java default methods, which delegation with `by` leaves answering as the interface has them: refused like the rest
    override fun getMaxLogicalLobSize(): Long = refusal.maxLogicalLobSize

    override fun supportsRefCursors(): Boolean = refusal.supportsRefCursors()

    override fun supportsSharding(): Boolean = refusal.supportsSharding()
}

private val refusal: DatabaseMetaData = Proxy.newProxyInstance(
    DatabaseMetaData::class.java.classLoader,
    arrayOf(DatabaseMetaData::class.java)
) { _, method, _ ->
    throw InvalidOperationException(
        InvalidOperationExceptionReason.FEATURE_NOT_SUPPORTED,
        details = "DatabaseMetaData.${method.name} is not supported"
    )
} as DatabaseMetaData
