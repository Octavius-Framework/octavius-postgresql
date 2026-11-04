package io.github.octaviusframework.driver.notice

import io.github.octaviusframework.driver.message.backend.ErrorOrNoticeMessage

/**
 * A notice or warning from the server, with the fields an error carries; [schema] through [constraint] are what
 * `RAISE ... USING` fills in.
 *
 * @property processId The backend of the connection it arrived on, which tells apart the connections of a shared
 *   handler; `-1` for a notice before the backend's key data.
 * @property severity `NOTICE`, `WARNING`, `INFO`, `DEBUG` or `LOG`, always in English, so safe to compare.
 * @property localizedSeverity The severity in the server's `lc_messages`, for showing to a person.
 * @property code The SQLSTATE.
 * @property position The 1-based character position in the statement.
 * @property internalQuery The server-generated statement it arose in, such as a PL/pgSQL body.
 * @property where The PL/pgSQL call stack.
 * @property file The PostgreSQL source file that reported it.
 * @property routine The PostgreSQL C function that reported it.
 */
data class PgNotice(
    val processId: Int,
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
        fun from(processId: Int, noticeMsg: ErrorOrNoticeMessage): PgNotice = PgNotice(
            processId = processId,
            severity = noticeMsg.severity,
            localizedSeverity = noticeMsg.localizedSeverity,
            code = noticeMsg.code,
            message = noticeMsg.message,
            detail = noticeMsg.detail,
            hint = noticeMsg.hint,
            position = noticeMsg.position,
            internalPosition = noticeMsg.internalPosition,
            internalQuery = noticeMsg.internalQuery,
            where = noticeMsg.where,
            schema = noticeMsg.schema,
            table = noticeMsg.table,
            column = noticeMsg.column,
            datatype = noticeMsg.datatype,
            constraint = noticeMsg.constraint,
            file = noticeMsg.file,
            line = noticeMsg.line,
            routine = noticeMsg.routine
        )
    }

    override fun toString(): String = buildString {
        append("[PID: $processId] Postgres $severity [$code]: $message")
        if (detail != null) append(" | Detail: $detail")
        if (hint != null) append(" | Hint: $hint")
        if (where != null) append(" | Where: $where")
    }
}
