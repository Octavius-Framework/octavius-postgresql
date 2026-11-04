package io.github.octaviusframework.driver.message

import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage

/**
 * Every field of a server error, on
 * [OctaviusException.serverErrorMessage][io.github.octaviusframework.driver.exception.OctaviusException.serverErrorMessage]
 * of whatever the server raised.
 *
 * @property severity `ERROR`, `FATAL` or `PANIC`, always in English, so safe to compare.
 * @property localizedSeverity The severity in the server's `lc_messages`, for showing to a person.
 * @property code The SQLSTATE.
 * @property position The 1-based character position in the statement.
 * @property internalQuery The server-generated statement it arose in, such as a PL/pgSQL body.
 * @property where The call stack.
 * @property file The PostgreSQL source file that reported it.
 * @property routine The PostgreSQL C function that reported it.
 */
data class ServerErrorMessage(
    val severity: String,
    val localizedSeverity: String,
    val code: String,
    val message: String,
    val detail: String?,
    val hint: String?,
    val position: Int?,
    val internalPosition: Int?,
    val internalQuery: String?,
    val where: String?,
    val schema: String?,
    val table: String?,
    val column: String?,
    val datatype: String?,
    val constraint: String?,
    val file: String?,
    val line: Int?,
    val routine: String?
) {
    internal companion object {
        fun from(errorMsg: ErrorOrNoticeMessage): ServerErrorMessage {
            return ServerErrorMessage(
                severity = errorMsg.severity,
                localizedSeverity = errorMsg.localizedSeverity,
                code = errorMsg.code,
                message = errorMsg.message,
                detail = errorMsg.detail,
                hint = errorMsg.hint,
                position = errorMsg.position,
                internalPosition = errorMsg.internalPosition,
                internalQuery = errorMsg.internalQuery,
                where = errorMsg.where,
                schema = errorMsg.schema,
                table = errorMsg.table,
                column = errorMsg.column,
                datatype = errorMsg.datatype,
                constraint = errorMsg.constraint,
                file = errorMsg.file,
                line = errorMsg.line,
                routine = errorMsg.routine
            )
        }
    }
}