package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/** One server, its host as written: `verify-full` matches it, and each address it resolves to is tried. */
internal data class ServerAddress(val host: String, val port: Int) : Comparable<ServerAddress> {

    /** `host:port`, an IPv6 host in brackets. */
    override fun toString(): String = "${if (':' in host) "[$host]" else host}:$port"

    override fun compareTo(other: ServerAddress): Int = compareValuesBy(this, other, { it.host }, { it.port })

    /**
     * One server as a list writes it: the host, and the port where one is stated.
     *
     * @property text The entry as written, quoted back in a refusal.
     */
    class Entry(val text: String, val host: String, val port: Int?)

    companion object {
        /**
         * The servers [text] lists, [defaultPort] for those that state none.
         *
         * @param key The property, named in a refusal.
         * @throws InvalidOperationException `INVALID_ARGUMENT` for an entry [entries] refuses, or one naming no host.
         */
        fun parseList(key: String, text: String, defaultPort: Int): List<ServerAddress> =
            entries(key, text).map { ServerAddress(it.host.ifEmpty { refuse(key, text, it.text) }, it.port ?: defaultPort) }

        /**
         * The entries of a comma-separated list: `host`, `host:port`, a bare IPv6 address, or a bracketed one with
         * or without a port. An entry may name no host, as `:5433` does.
         *
         * @param key The property, named in a refusal.
         * @throws InvalidOperationException `INVALID_ARGUMENT` for an unclosed bracket, or a port that is not a
         *   whole number.
         */
        fun entries(key: String, text: String): List<Entry> = text.split(',').map { entry(key, text, it.trim()) }

        private fun entry(key: String, list: String, text: String): Entry {
            if (text.startsWith('[')) {
                val close = text.indexOf(']')
                if (close == -1) refuse(key, list, text)
                val rest = text.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> null
                    rest.startsWith(':') -> port(key, list, text, rest.substring(1))
                    else -> refuse(key, list, text)
                }
                return Entry(text, text.substring(1, close), port)
            }
            return when (text.count { it == ':' }) {
                1 -> Entry(text, text.substringBefore(':'), port(key, list, text, text.substringAfter(':')))
                else -> Entry(text, text, null)
            }
        }

        private fun port(key: String, list: String, text: String, value: String): Int =
            value.toIntOrNull() ?: refuse(key, list, text)

        /** Refuses [text], an entry of [list] given under [key]. */
        fun refuse(key: String, list: String, text: String): Nothing = throw InvalidOperationException(
            InvalidOperationExceptionReason.INVALID_ARGUMENT,
            details = "Property '$key' was '$list', and '$text' in it is not a host, a host:port or a " +
                "[bracketed IPv6]:port."
        )
    }
}
