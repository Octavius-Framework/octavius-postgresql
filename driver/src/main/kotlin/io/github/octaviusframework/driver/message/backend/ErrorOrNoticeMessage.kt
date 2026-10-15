package io.github.octaviusframework.driver.message.backend

/**
 * The fields of an ErrorResponse (Tag 'E') or of a NoticeResponse (Tag 'N').
 *
 * The two messages carry the same field set and differ only in their tag and in what [severity]
 * says, so one class reads both. An error is returned from the stream and becomes an exception
 * through [ExceptionTranslator][io.github.octaviusframework.driver.message.translator.ExceptionTranslator];
 * a notice never leaves the stream and becomes a
 * [PgNotice][io.github.octaviusframework.driver.notice.PgNotice] there.
 *
 * [severity], [localizedSeverity], [code] and [message] are the fields the protocol requires of
 * either message; which of the rest arrive depends on what raised it.
 */
internal class ErrorOrNoticeMessage(val fields: Map<Char, String>) : BackendMessage {

    companion object {
        private const val SEVERITY_LOCALIZED: Char = 'S'
        private const val SEVERITY_NON_LOCALIZED: Char = 'V'
        private const val SQLSTATE: Char = 'C'
        private const val MESSAGE: Char = 'M'
        private const val DETAIL: Char = 'D'
        private const val HINT: Char = 'H'
        private const val POSITION: Char = 'P'
        private const val INTERNAL_POSITION: Char = 'p'
        private const val INTERNAL_QUERY: Char = 'q'
        private const val WHERE: Char = 'W'
        private const val SCHEMA: Char = 's'
        private const val TABLE: Char = 't'
        private const val COLUMN: Char = 'c'
        private const val DATATYPE: Char = 'd'
        private const val CONSTRAINT: Char = 'n'
        private const val FILE: Char = 'F'
        private const val LINE: Char = 'L'
        private const val ROUTINE: Char = 'R'
    }

    val localizedSeverity: String get() = fields[SEVERITY_LOCALIZED]!!

    val severity: String get() = fields[SEVERITY_NON_LOCALIZED]!!

    /**
     * Whether this is an error that ends the session: a `FATAL`, or a `PANIC`, which ends every session on the
     * server. The server closes the connection behind either one, so nothing follows it - no `ReadyForQuery`.
     */
    val isFatal: Boolean get() = severity == "FATAL" || severity == "PANIC"

    val code: String get() = fields[SQLSTATE]!!

    val message: String get() = fields[MESSAGE]!!
    val detail: String? get() = fields[DETAIL]
    val hint: String? get() = fields[HINT]
    val position: Int? get() = fields[POSITION]?.toIntOrNull()
    val internalPosition: Int? get() = fields[INTERNAL_POSITION]?.toIntOrNull()
    val internalQuery: String? get() = fields[INTERNAL_QUERY]
    val where: String? get() = fields[WHERE]
    val schema: String? get() = fields[SCHEMA]
    val table: String? get() = fields[TABLE]
    val column: String? get() = fields[COLUMN]
    val datatype: String? get() = fields[DATATYPE]
    val constraint: String? get() = fields[CONSTRAINT]
    val file: String? get() = fields[FILE]
    val line: Int? get() = fields[LINE]?.toIntOrNull()
    val routine: String? get() = fields[ROUTINE]

    override fun toString(): String {
        return "ErrorOrNotice(severity=$severity, code=$code, message=$message, schema=$schema, table=$table, column=$column, constraint=$constraint)"
    }
}
