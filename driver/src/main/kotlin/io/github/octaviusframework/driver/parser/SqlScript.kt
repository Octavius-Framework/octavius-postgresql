package io.github.octaviusframework.driver.parser

/**
 * One statement out of a script, and where it stood in the text it was cut from.
 *
 * @property sql The statement itself, without the `;` that ended it and without the whitespace around it.
 * Comments that fell inside it are kept - they cost nothing to send and removing them would move every
 * position after them.
 * @property offset Index in the original script of this statement's first character. The server reports an
 * error position against what it was sent, so a statement sent on its own has its errors numbered from its
 * own beginning; adding this puts that number back where the author can find it.
 * @property keyword The statement's first word, upper-cased, with whatever comments stand in front of it
 * stepped over - `COMMIT` for `-- why we do this
COMMIT`. `null` where the statement opens with something
 * that is not a word: a quote, a dollar-quoted body, a parenthesis. Upper-cased because SQL keywords are
 * case-insensitive and comparing them as written is a bug waiting to happen.
 */
data class SqlStatement(val sql: String, val offset: Int, val keyword: String?)

/**
 * Cuts a script into the statements it is made of.
 *
 * PostgreSQL takes a whole script in one message and runs it inside an implicit transaction, which is what
 * is wanted right up until one of the statements is `CREATE INDEX CONCURRENTLY`, `VACUUM`, or anything else
 * the server refuses inside a transaction block. Those have to arrive one message at a time, and sending
 * them one at a time means knowing where each one ends.
 *
 * Nothing here parses SQL. A statement is the text between separators; whether the server will accept it is
 * the server's answer to give. The one place words are read at all is a standard-SQL function body, which is
 * not quoted and so cannot be skipped the way a dollar-quoted one is.
 */
object SqlScript {

    /**
     * Splits [sql] on the `;` that separate statements, and on no other `;`.
     *
     * A `;` does not separate anything when it stands inside `'...'`, inside `E'...'` where a backslash
     * escapes what follows it, inside `"..."`, inside `$tag$...$tag$` - which is where a PL/pgSQL body keeps
     * most of its semicolons - inside a `--` comment, inside a `/* */` comment nested to any depth, inside
     * parentheses, where `CREATE RULE ... DO (a; b)` puts one legitimately, or inside the `BEGIN ATOMIC ...
     * END` body of a `CREATE FUNCTION` or `CREATE PROCEDURE`.
     *
     * @param sql The script, one statement or many.
     * @return The statements in the order they appear. A stretch holding nothing but whitespace and comments
     * is not a statement and is not returned, so a trailing `;` adds nothing and a script of comments alone
     * comes back empty.
     * @throws io.github.octaviusframework.driver.exception.StatementException `UNCLOSED_TOKEN` where a
     * quote, dollar quote or block comment opens and never closes. The whole point of the walk is telling
     * which `;` are separators, and past an unterminated construct none of them can be told apart.
     */
    fun split(sql: String): List<SqlStatement> {
        val statements = mutableListOf<SqlStatement>()

        var i = 0
        var start = 0
        var parenDepth = 0
        // Where the first thing that makes this a statement rather than a gap between statements began.
        // Whitespace and comments are neither, so it stays -1 across them - which answers both "is there a
        // statement here at all" and "where does its first word start".
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
                    // Clamped rather than allowed to go negative: a script with an unbalanced `)` in it is
                    // the server's to refuse, and a negative depth here would hide every separator after it.
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
 * Whether the statement being walked stands inside a standard-SQL routine body, `BEGIN ATOMIC ... END`, where a
 * `;` ends a statement of the body and not of the script.
 *
 * Such a body is SQL the server parses rather than a string the scanner could step over, so this goes by words
 * instead, the way psql's own lexer does: in a statement that opens `CREATE [OR REPLACE] FUNCTION` or
 * `PROCEDURE`, a `BEGIN` at the top level opens a block, a `CASE` inside one opens another - it ends with `END`
 * as well - and an `END` closes one. A `RETURN` body has no block, so nothing opens and its `;` separates as
 * any other.
 *
 * Fed only the words outside parentheses: a `CASE ... END` inside them closes inside them too, so counting
 * neither half changes nothing. A word straight after a `.` or after `AS` is a name, whatever it spells -
 * `f.end`, `AS end` - and is not counted, where psql would count it.
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
