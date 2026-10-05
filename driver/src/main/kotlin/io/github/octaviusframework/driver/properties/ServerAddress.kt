package io.github.octaviusframework.driver.properties

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * One server a connection may be opened to: the host as it was written, and the port that goes with it.
 *
 * The host stays as written rather than resolved. It is what a certificate is matched against under
 * `verify-full`, and a name can resolve to several addresses, each of which is tried in turn.
 */
internal data class ServerAddress(val host: String, val port: Int) : Comparable<ServerAddress> {

    /** `host:port`, with an IPv6 address in brackets so that the colon before the port is told from its own. */
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
         * Reads a comma-separated list of servers, each [defaultPort] where it states no port of its own.
         *
         * @param key The property the list was given under, quoted back in a refusal.
         * @param text The list.
         * @param defaultPort The port of every entry that states none.
         * @return The servers, in the order listed.
         * @throws InvalidOperationException `INVALID_ARGUMENT` for an entry [entries] refuses, or one naming no host.
         */
        fun parseList(key: String, text: String, defaultPort: Int): List<ServerAddress> =
            entries(key, text).map { ServerAddress(it.host.ifEmpty { refuse(key, text, it.text) }, it.port ?: defaultPort) }

        /**
         * Reads a comma-separated list of servers as written, each one a `host`, a `host:port`, a bare IPv6
         * address, or a bracketed IPv6 address with or without a port.
         *
         * An IPv6 address carries colons of its own, so one with a port has to be bracketed; a bare one is told
         * from a `host:port` by having more than one colon, and has no room for a port. An entry may name no
         * host - a URL's `:5433` states only a port - and whether that will do is for the caller to say.
         *
         * @param key The property the list was given under, quoted back in a refusal.
         * @param text The list.
         * @return The entries, in the order listed.
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
