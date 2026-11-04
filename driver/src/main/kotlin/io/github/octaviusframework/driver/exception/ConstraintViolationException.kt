package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/** Which constraint was violated. */
enum class ConstraintViolationExceptionReason {
    /** A duplicate in a unique column or index (23505). */
    UNIQUE_CONSTRAINT_VIOLATION,

    /** A key the referenced table lacks, or a referenced row deleted or rekeyed (23503, 23001 under `RESTRICT`). */
    FOREIGN_KEY_VIOLATION,

    /** A `NULL` in a `NOT NULL` column (23502). */
    NOT_NULL_VIOLATION,

    /** A value a `CHECK` constraint refuses (23514). */
    CHECK_CONSTRAINT_VIOLATION,

    /** A row an exclusion constraint refuses (23P01). */
    EXCLUSION_CONSTRAINT_VIOLATION,

    /** Any other constraint violation, such as 23000 or 40002 raised by a function or an extension. */
    UNKNOWN
}

/**
 * A statement violated a constraint.
 *
 * @property dbMessage The server's message.
 * @property details The server's detail; for a unique violation, the offending key and value.
 * @property where The call stack, for a violation raised inside a trigger or function.
 */
class ConstraintViolationException(
    val reason: ConstraintViolationExceptionReason,
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException(
    message = "CONSTRAINT_VIOLATION_EXCEPTION:${reason.name}",
    sqlState = sqlState,
    serverErrorMessage = serverErrorMessage
) {
    val dbMessage: String get() = serverErrorMessage!!.message
    val details: String? get() = serverErrorMessage!!.detail
    val where: String? get() = serverErrorMessage!!.where
    val schema: String? get() = serverErrorMessage!!.schema
    val table: String? get() = serverErrorMessage!!.table
    val column: String? get() = serverErrorMessage!!.column
    val constraint: String? get() = serverErrorMessage?.constraint

    override fun getDetailedMessage(): String = buildString {
        appendLine("Reason: ${generateDeveloperMessage(reason)}")
        appendLine("Database Message: $dbMessage")
        if (details != null) appendLine("Details: $details")
        if (where != null) appendLine("Context: $where")
        if (schema != null) appendLine("Schema: $schema")
        if (table != null) appendLine("Table: $table")
        if (column != null) appendLine("Column: $column")
        if (constraint != null) appendLine("Constraint: $constraint")
    }
}

private fun generateDeveloperMessage(reason: ConstraintViolationExceptionReason): String =
    when (reason) {
        ConstraintViolationExceptionReason.UNIQUE_CONSTRAINT_VIOLATION -> "A duplicate value was provided for a unique column or index (PostgreSQL 23505)."
        ConstraintViolationExceptionReason.FOREIGN_KEY_VIOLATION -> "A value was provided that does not exist in the referenced table, or a referenced row was deleted or had its key changed (PostgreSQL 23503, 23001)."
        ConstraintViolationExceptionReason.NOT_NULL_VIOLATION -> "A null value was provided for a non-nullable column (PostgreSQL 23502)."
        ConstraintViolationExceptionReason.CHECK_CONSTRAINT_VIOLATION -> "A value was provided that fails a CHECK constraint (PostgreSQL 23514)."
        ConstraintViolationExceptionReason.EXCLUSION_CONSTRAINT_VIOLATION -> "Exclusion constraint violations (PostgreSQL 23P01)."
        ConstraintViolationExceptionReason.UNKNOWN -> "Unknown constraint violation."
    }
