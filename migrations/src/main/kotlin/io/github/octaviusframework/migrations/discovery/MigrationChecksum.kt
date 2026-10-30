package io.github.octaviusframework.migrations.discovery

import java.util.zip.CRC32

/**
 * The number a `.sql` migration is recognised by on every run after the first.
 */
internal object MigrationChecksum {

    /**
     * The checksum of [content], after normalising away the differences a checkout can introduce.
     *
     * Normalised: a leading byte-order mark, `\r\n` and lone `\r` line endings, and whitespace at the very
     * end of the file - an editor adding or removing the final newline is not an edit to the migration.
     * Everything else counts, whitespace inside the file included: reformatting a migration that has already
     * run is a change to a file the database has a record of, and saying so is the point.
     *
     * @return The CRC32
     */
    fun of(content: String): Long {
        val crc = CRC32()
        crc.update(normalize(content).toByteArray(Charsets.UTF_8))
        return crc.value
    }

    private fun normalize(content: String): String =
        content.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trimEnd()
}
