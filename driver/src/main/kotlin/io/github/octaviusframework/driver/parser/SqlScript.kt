package io.github.octaviusframework.driver.parser

/**
 * One statement of a script.
 *
 * @property sql The statement, trimmed, without its `;`; comments inside it are kept.
 * @property offset Where it starts in the script, to add to an error position the server reports.
 * @property keyword Its first word past any comments, upper-cased, or `null` where it opens with something else.
 */
data class SqlStatement(val sql: String, val offset: Int, val keyword: String?)

/**
 * Cuts a script into statements, for those the server refuses inside the implicit transaction of a script, such
 * as `CREATE INDEX CONCURRENTLY`. Nothing is parsed beyond finding separators.
 */
object SqlScript {

    /**
     * The statements of [sql], split on every `;` outside quotes, dollar quotes, comments, parentheses and a
     * `BEGIN ATOMIC ... END` body. Whitespace and comments alone are no statement.
     *
     * @throws io.github.octaviusframework.driver.exception.StatementException `UNCLOSED_TOKEN` where a
     * quote, dollar quote or block comment never closes.
     */
    fun split(sql: String): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()

        var i = 0
        var start = 0
        var parenDepth = 0
        // Where the statement's first token began, -1 across whitespace and comments.
        var contentStart = -1
        val body = AtomicBody()

        while (i < sql.length) {
            val constructEnd = SqlScanner.findConstructEnd(sql, i)
            if (constructEnd > i) {
                if (!SqlScanner.isCommentStart(sql, i)) {
                    if (contentStart < 0) contentStart = i
                    body.other()
                }
                i = constructEnd + 1
                continue
            }

            val c = sql[i]
            if (c == ';' && parenDepth == 0 && !body.isOpen) {
                if (contentStart >= 0) addStatement(sql, start, i, contentStart, statements)
                start = i + 1
                contentStart = -1
                body.reset()
            } else if (isWordStart(c)) {
                // Read whole, so a word is never entered halfway: `legend` holds no END.
                var wordEnd = i + 1
                while (wordEnd < sql.length && isWordPart(sql[wordEnd])) wordEnd++
                if (contentStart < 0) contentStart = i
                if (parenDepth == 0) body.word(sql.substring(i, wordEnd), afterDot = i > 0 && sql[i - 1] == '.')
                i = wordEnd
                continue
            } else {
                if (!c.isWhitespace()) body.other()
                if (c == '(') {
                    parenDepth++
                } else if (c == ')' && parenDepth > 0) {
                    // Clamped: an unbalanced `)` is the server's to refuse, not a reason to hide later separators.
                    parenDepth--
                }
                if (!c.isWhitespace() && contentStart < 0) contentStart = i
            }
            i++
        }

        if (contentStart >= 0) addStatement(sql, start, sql.length, contentStart, statements)

        return statements
    }

    /** Trims the whitespace off `sql[start until end]` and files what is left, offset and keyword and all. */
    private fun addStatement(
        sql: String,
        start: Int,
        end: Int,
        contentStart: Int,
        into: MutableList<SqlStatement>
    ) {
        var from = start
        var to = end
        while (from < to && sql[from].isWhitespace()) from++
        while (to > from && sql[to - 1].isWhitespace()) to--
        if (from >= to) return

        var wordEnd = contentStart
        while (wordEnd < to && sql[wordEnd].isLetter()) wordEnd++
        val keyword = if (wordEnd > contentStart) sql.substring(contentStart, wordEnd).uppercase() else null

        into.add(SqlStatement(sql.substring(from, to), from, keyword))
    }

    /** What starts a word the way PostgreSQL's lexer starts an identifier or keyword. */
    private fun isWordStart(c: Char): Boolean = c.isLetter() || c == '_' || (c.code >= 0x80 && !c.isWhitespace())

    /** What continues one: a `$` included, so `end$1` is a single name and not END. */
    private fun isWordPart(c: Char): Boolean = isWordStart(c) || c.isDigit() || c == '$'
}

/**
 * Whether the walk stands inside a `BEGIN ATOMIC ... END` body, counted by words as psql does: in a `CREATE
 * FUNCTION` or `PROCEDURE`, `BEGIN` and `CASE` open a block and `END` closes one. Fed the words outside
 * parentheses; a word after `.` or `AS` is a name and not counted.
 */
private class AtomicBody {
    private val opening = ArrayList<String>(4)
    private var depth = 0
    private var previous: String? = null

    val isOpen: Boolean get() = depth > 0

    fun word(word: String, afterDot: Boolean) {
        val keyword = word.uppercase()
        val isName = afterDot || previous == "AS"
        previous = keyword
        if (opening.size < 4) opening += keyword
        if (isName || !definesRoutine()) return

        when (keyword) {
            "BEGIN" -> depth++
            "CASE" -> if (depth > 0) depth++
            "END" -> if (depth > 0) depth--
        }
    }

    /** Anything that is not a word and not a comment: what stood before the next word is no longer `AS`. */
    fun other() {
        previous = null
    }

    fun reset() {
        opening.clear()
        depth = 0
        previous = null
    }

    private fun definesRoutine(): Boolean {
        if (opening.getOrNull(0) != "CREATE") return false
        val routine = if (opening.getOrNull(1) == "OR" && opening.getOrNull(2) == "REPLACE") opening.getOrNull(3) else opening.getOrNull(1)
        return routine == "FUNCTION" || routine == "PROCEDURE"
    }
}
