package io.github.octaviusframework.migrations.discovery

import io.github.octaviusframework.driver.parser.SqlScript
import io.github.octaviusframework.migrations.MigrationException
import io.github.octaviusframework.migrations.MigrationExceptionReason

/**
 * What a `.sql` migration is allowed to say, checked while the database is still untouched.
 *
 * Both checks here could be left to the server, and both are worth making early instead. One of them the
 * server would not catch at all.
 */
internal object SqlMigrationRules {

    private const val DIRECTIVE_PREFIX = "octavius:"
    private const val NO_TRANSACTION = "no-transaction"

    /**
     * Statements that would take the run's transaction with them.
     *
     * `END` and `ABORT` are in here because PostgreSQL accepts them as `COMMIT` and `ROLLBACK`. The `END`
     * that closes a PL/pgSQL block is not one of these - it lives inside a dollar-quoted body - and neither
     * is the one closing a `BEGIN ATOMIC` body; the splitter surfaces neither as a statement of its own.
     */
    private val TRANSACTION_CONTROL = setOf("BEGIN", "START", "COMMIT", "END", "ROLLBACK", "ABORT")

    /**
     * Whether [content] wants a transaction, which is everything except a file that said otherwise.
     *
     * A directive is a comment in the file's header - before the first line that is neither blank nor a
     * comment - shaped `-- octavius:no-transaction`. Spacing and case are free; the name is not. A directive
     * nobody knows is refused rather than ignored, because a typo in `no-transaction` that passed silently
     * would put a `CREATE INDEX CONCURRENTLY` inside a transaction and the server would refuse it there.
     *
     * A directive **below** the header is refused too: one buried at line four hundred, changing how the
     * whole file runs, is not something a reader should have to go looking for.
     *
     * @throws MigrationException `INVALID_MIGRATION` for a directive that is unknown or out of place.
     */
    fun readTransactionality(content: String, origin: String): Boolean {
        var transactional = true
        var headerOver = false

        content.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (!trimmed.startsWith("--")) {
                headerOver = true
                return@forEachIndexed
            }

            val directive = directiveIn(trimmed) ?: return@forEachIndexed
            if (headerOver) {
                throw invalid(
                    origin,
                    "line ${index + 1} carries '-- $DIRECTIVE_PREFIX$directive' below the first statement. A " +
                        "directive governs the whole file, so it belongs in the header, above everything else."
                )
            }
            when (directive) {
                NO_TRANSACTION -> transactional = false
                else -> throw invalid(
                    origin,
                    "line ${index + 1} declares '-- $DIRECTIVE_PREFIX$directive', which is not a directive " +
                        "this understands. The only one is '$NO_TRANSACTION'."
                )
            }
        }

        return transactional
    }

    /**
     * Refuses a script that opens, commits or rolls back a transaction of its own.
     *
     * This is the check the server would never make. Under a transactional migration the run has a
     * transaction open and puts the history row in it; a `COMMIT` in the middle of the file ends that
     * transaction, and the row recording the migration lands outside it - so the file half-applied would be
     * recorded as applied whole. Without a transaction it is no better: a `BEGIN` there wraps the rest of
     * the file, which is the one thing the file asked not to happen.
     *
     * @throws MigrationException `INVALID_MIGRATION` naming the statement and where it is.
     */
    fun refuseTransactionControl(content: String, origin: String) {
        for (statement in SqlScript.split(content)) {
            val keyword = statement.keyword ?: continue
            if (keyword in TRANSACTION_CONTROL) {
                throw invalid(
                    origin,
                    "the statement at offset ${statement.offset} is '$keyword', and a migration does not get " +
                        "to control the transaction it runs in - the run owns that, and the record of this " +
                        "migration goes into it. For a migration that must run outside one, put " +
                        "'-- $DIRECTIVE_PREFIX$NO_TRANSACTION' in the header instead."
                )
            }
        }
    }

    /** The directive named by a comment line, or `null` where the line is an ordinary comment. */
    private fun directiveIn(commentLine: String): String? {
        val body = commentLine.removePrefix("--").trim().lowercase().replace(WHITESPACE, " ")
        if (!body.startsWith(DIRECTIVE_PREFIX)) return null
        return body.removePrefix(DIRECTIVE_PREFIX).trim()
    }

    private val WHITESPACE = Regex("\\s+")

    private fun invalid(origin: String, problem: String) =
        MigrationException(MigrationExceptionReason.INVALID_MIGRATION, "$origin: $problem")
}
