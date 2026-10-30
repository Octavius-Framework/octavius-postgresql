package io.github.octaviusframework.migrations

/**
 * A migration's version, ordered the way version numbers are ordered rather than the way text is.
 *
 * `1.9` comes before `1.10`. Parts are held as `Long`, so a version written as a timestamp -
 * `20260827120000` - fits.
 *
 * **Missing parts are zero, so `1`, `1.0` and `1.0.0` are one version, not three.** They compare equal, they
 * hash alike, and two migrations carrying them are a duplicate rather than a sequence. [text] keeps whichever
 * of them was actually written; [canonical] is the spelling that goes into the history table.
 */
class MigrationVersion private constructor(
    private val parts: List<Long>,

    /**
     * The version as it was written - `2_1` where it came off a class name, `1.0` where the file said so.
     *
     * For a message about *that* file or class, which should echo what its author typed. Everywhere else
     * wants [canonical].
     */
    val text: String
) : Comparable<MigrationVersion> {

    /**
     * The version with `_` written as `.` - a class name cannot hold one - which is what goes in the history
     * table and in a report. Trailing zeroes stay as written; whether two versions are the same is settled by
     * their parts, never by this string.
     */
    val canonical: String get() = text.replace('_', '.')

    override fun compareTo(other: MigrationVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val mine = parts.getOrElse(i) { 0L }
            val theirs = other.parts.getOrElse(i) { 0L }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    // Compared and hashed on the canonical parts rather than on [text]: `1.0` and `1` are the same version,
    // and a set that let both in would report no duplicate where the run would then find one.
    override fun equals(other: Any?): Boolean = other is MigrationVersion && parts == other.parts

    override fun hashCode(): Int = parts.hashCode()

    override fun toString(): String = canonical

    companion object {

        /**
         * Reads a version out of [text], or refuses.
         *
         * Parts are separated by `.` or by `_`, the two being interchangeable: a file can be called
         * `V2.1__x.sql` or `V2_1__x.sql`, and a class has no choice, `.` not being legal in an identifier.
         * Every part has to be digits and nothing else - no sign, no letters, no spaces - which is what keeps
         * `1.x.2` from quietly becoming `1.2`.
         *
         * @throws MigrationException `INVALID_MIGRATION` where [text] is empty, holds a part that is not a
         * number, or holds one too large for a `Long`.
         */
        fun parse(text: String): MigrationVersion = parse(text, text)

        internal fun parse(text: String, origin: String): MigrationVersion {
            if (text.isEmpty()) {
                throw MigrationException(MigrationExceptionReason.INVALID_MIGRATION, "$origin: the version is empty.")
            }

            val parts = text.split('.', '_').map { part ->
                if (part.isEmpty()) {
                    throw MigrationException(
                        MigrationExceptionReason.INVALID_MIGRATION,
                        "$origin: the version \"$text\" has an empty part - two separators in a row, or one " +
                            "at an end."
                    )
                }
                if (part.any { it < '0' || it > '9' }) {
                    throw MigrationException(
                        MigrationExceptionReason.INVALID_MIGRATION,
                        "$origin: \"$part\" in the version \"$text\" is not a number. A version is digits " +
                            "separated by '.' or '_', and nothing else."
                    )
                }
                part.toLongOrNull() ?: throw MigrationException(
                    MigrationExceptionReason.INVALID_MIGRATION,
                    "$origin: \"$part\" in the version \"$text\" is too large to be a version part."
                )
            }

            return MigrationVersion(withoutTrailingZeroes(parts), text)
        }

        /**
         * Drops trailing zeroes so that `1.0.0` and `1` hold the same parts, keeping one part either way -
         * version `0` is a version, and an empty list would compare equal to everything.
         */
        private fun withoutTrailingZeroes(parts: List<Long>): List<Long> {
            var end = parts.size
            while (end > 1 && parts[end - 1] == 0L) end--
            return parts.subList(0, end)
        }
    }
}
