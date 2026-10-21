package io.github.octaviusframework.driver.jdbc

/**
 * A session's `search_path`, read from what the server reports, and the order the server looks a type name up in.
 *
 * The report is the setting's text, which is not the path the server searches. `SET` normalizes what it is given
 * before storing it, but `set_config` and a startup parameter store the text as written, so `Gallia` arrives as
 * `Gallia` and means `gallia`. The setting also names schemas by alias - `$user`, `pg_temp` - and leaves out the
 * ones searched without being listed. Both halves are worked out here the way `namespace.c` works them out.
 *
 * What the server knows and does not report, this cannot follow:
 * - `$user` is the current role, and `SET ROLE` moves it without a `ParameterStatus` (`role` is not reported in
 *   PostgreSQL 18). It is taken from `session_authorization`, which `SET SESSION AUTHORIZATION` does report.
 * - A listed schema the current role has no `USAGE` on is skipped by the server and kept here.
 * - The session's temporary schema is a `pg_temp_N` created with its first temporary object, and reported nowhere.
 *   The server searches it ahead of everything else for type names, `pg_catalog` included, or where `pg_temp`
 *   stands in the setting; it is left out here either way.
 *
 * @property setting the reported `search_path`, as it arrived.
 * @property sessionUser the reported `session_authorization`, which `$user` stands for, or `null` where none was.
 */
internal class SearchPath(val setting: String, val sessionUser: String?) {

    /**
     * The setting's entries, split as the server splits them: an unquoted name folded to lower case, a quoted
     * one unquoted, every name cut to [MAX_NAME_BYTES]. `$user` and `pg_temp` stay as they are.
     */
    val entries: List<String> = splitIdentifiers(setting)

    /**
     * The schemas an unqualified type name is looked up in, first match winning: [entries] with `$user` as
     * [sessionUser], or without it where there is none, without `pg_temp`, and with `pg_catalog` ahead of them
     * unless they list it.
     */
    val typeLookupOrder: List<String> = buildList {
        for (entry in entries) {
            when (entry) {
                USER_ALIAS -> sessionUser?.let { add(it) }
                TEMP_ALIAS -> {}
                else -> add(entry)
            }
        }
        if (CATALOG_SCHEMA !in this) add(0, CATALOG_SCHEMA)
    }

    /** Whether this was read from these two reports, and so still holds while they are what the server says. */
    fun isFor(setting: String, sessionUser: String?): Boolean =
        this.setting == setting && this.sessionUser == sessionUser

    companion object {
        private const val USER_ALIAS = "\$user"
        private const val TEMP_ALIAS = "pg_temp"
        private const val CATALOG_SCHEMA = "pg_catalog"

        /** `NAMEDATALEN - 1` in a stock build: the longest name a catalog holds, in the server's encoding. */
        private const val MAX_NAME_BYTES = 63

        /**
         * `SplitIdentifierString` over a list the server has already accepted, so there is no syntax to refuse.
         *
         * The folding is ASCII only, which is what the server does in a multibyte encoding; in a single-byte one
         * it folds the letters past ASCII too.
         */
        private fun splitIdentifiers(setting: String): List<String> {
            val names = ArrayList<String>()
            var i = 0
            fun skipSpace() {
                while (i < setting.length && setting[i].isScannerSpace()) i++
            }

            skipSpace()
            while (i < setting.length) {
                val name = if (setting[i] == '"') {
                    val quoted = StringBuilder()
                    i++
                    while (i < setting.length) {
                        val c = setting[i++]
                        if (c != '"') quoted.append(c)
                        else if (i < setting.length && setting[i] == '"') quoted.append(setting[i++])
                        else break
                    }
                    quoted.toString()
                } else {
                    val start = i
                    while (i < setting.length && setting[i] != ',' && !setting[i].isScannerSpace()) i++
                    setting.substring(start, i).foldedToLowerCase()
                }
                // A quoted "" is accepted and names nothing.
                if (name.isNotEmpty()) names += name.truncatedToNameLength()
                skipSpace()
                if (i < setting.length && setting[i] == ',') i++
                skipSpace()
            }
            return names
        }

        private fun Char.isScannerSpace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000C' || this == '\u000B'

        private fun String.foldedToLowerCase(): String = buildString(length) {
            for (c in this@foldedToLowerCase) append(if (c in 'A'..'Z') c + ('a' - 'A') else c)
        }

        private fun String.truncatedToNameLength(): String {
            var bytes = 0
            var i = 0
            while (i < length) {
                val codePoint = codePointAt(i)
                bytes += when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
                if (bytes > MAX_NAME_BYTES) return substring(0, i)
                i += Character.charCount(codePoint)
            }
            return this
        }
    }
}
