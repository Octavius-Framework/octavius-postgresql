package io.github.octaviusframework.driver.jdbc

/**
 * A reported `search_path` read the way `namespace.c` reads it: the setting's text, unnormalized where it came
 * from `set_config` or a startup parameter, and the order a type name is looked up in. What the server does not
 * report stays out of it: `$user` follows `session_authorization` and not `SET ROLE`, a schema without `USAGE` is
 * kept, and the session's temporary schema is left out.
 *
 * @property sessionUser The reported `session_authorization`, which `$user` stands for, or `null`.
 */
internal class SearchPath(val setting: String, val sessionUser: String?) {

    /**
     * The setting's entries: unquoted names folded to lower case, quoted ones unquoted, every name cut to
     * [MAX_NAME_BYTES]. `$user` and `pg_temp` stay as they are.
     */
    val entries: List<String> = splitIdentifiers(setting)

    /**
     * The schemas an unqualified type name is looked up in: [entries] with `$user` resolved or dropped, without
     * `pg_temp`, and with `pg_catalog` first unless they list it.
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

    /** Whether this was read from these two reports. */
    fun isFor(setting: String, sessionUser: String?): Boolean =
        this.setting == setting && this.sessionUser == sessionUser

    companion object {
        private const val USER_ALIAS = "\$user"
        private const val TEMP_ALIAS = "pg_temp"
        private const val CATALOG_SCHEMA = "pg_catalog"

        /** `NAMEDATALEN - 1` in a stock build: the longest name a catalog holds, in the server's encoding. */
        private const val MAX_NAME_BYTES = 63

        /**
         * `SplitIdentifierString` over a list the server already accepted. Folds ASCII only, as the server does in a
         * multibyte encoding.
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
