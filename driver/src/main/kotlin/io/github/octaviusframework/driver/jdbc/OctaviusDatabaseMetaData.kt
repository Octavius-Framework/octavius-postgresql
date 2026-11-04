package io.github.octaviusframework.driver.jdbc

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason
import java.lang.reflect.Proxy
import java.sql.DatabaseMetaData

/**
 * Answers [supportsSavepoints], which Spring asks before `Propagation.NESTED`, and [getDatabaseProductName], which
 * Spring Boot's `db` health indicator needs. Everything else raises `FEATURE_NOT_SUPPORTED`.
 */
internal object OctaviusDatabaseMetaData : DatabaseMetaData by refusal {
    override fun supportsSavepoints(): Boolean = true

    override fun getDatabaseProductName(): String = "PostgreSQL"

    // Java default methods, which `by` leaves answering as the interface has them: refused like the rest
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
