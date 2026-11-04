package io.github.octaviusframework.driver.exception

import io.github.octaviusframework.driver.message.ServerErrorMessage

/**
 * The user lacks a privilege the statement needs. The object refused is named where the server names it.
 *
 * @property dbMessage The server's message.
 */
class PermissionDeniedException(
    sqlState: String,
    serverErrorMessage: ServerErrorMessage
) : OctaviusException(
    message = "PERMISSION_DENIED_EXCEPTION",
    sqlState = sqlState,
    serverErrorMessage = serverErrorMessage
) {
    val dbMessage: String get() = serverErrorMessage!!.message
    val schema: String? get() = serverErrorMessage!!.schema
    val table: String? get() = serverErrorMessage!!.table
    val column: String? get() = serverErrorMessage!!.column
    val datatype: String? get() = serverErrorMessage!!.datatype
    val routine: String? get() = serverErrorMessage!!.routine
    override fun getDetailedMessage(): String = buildString {
        appendLine("Database message: $dbMessage")
        if (schema != null) appendLine("Schema: $schema")
        if (table != null) appendLine("Table: $table")
        if (column != null) appendLine("Column: $column")
        if (datatype != null) appendLine("Datatype: $datatype")
        if (routine != null) appendLine("Routine: $routine")
    }
}
