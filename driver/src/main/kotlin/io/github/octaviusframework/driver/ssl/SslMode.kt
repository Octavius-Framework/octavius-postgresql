package io.github.octaviusframework.driver.ssl

import io.github.octaviusframework.driver.exception.InvalidOperationException
import io.github.octaviusframework.driver.exception.InvalidOperationExceptionReason

/**
 * How much protection the connection demands, as `libpq` means it. Below [VERIFY_CA] nothing checks who is on the
 * other end; [ChannelBinding][io.github.octaviusframework.driver.auth.ChannelBinding] covers that without a CA.
 *
 * @property value The spelling in a URL and in `libpq`.
 */
enum class SslMode(val value: String) {
    /** Never encrypt. A server that demands TLS refuses the connection. */
    DISABLE("disable"),

    /** Encrypt if the server offers it, connect in plaintext if not. The default. */
    PREFER("prefer"),

    /** Refuse to connect unencrypted. The certificate itself is not checked. */
    REQUIRE("require"),

    /** Encrypt, and verify the server's certificate against `sslrootcert`, or the JVM's trust store. */
    VERIFY_CA("verify-ca"),

    /** As [VERIFY_CA], and additionally require the certificate to name the host being connected to. */
    VERIFY_FULL("verify-full");

    companion object {
        /**
         * The mode [value] spells or names, case-insensitively, or [PREFER] for `null`.
         *
         * @throws InvalidOperationException `INVALID_ARGUMENT` if [value] matches nothing.
         */
        fun of(value: String?): SslMode {
            if (value == null) return PREFER
            return entries.find { it.value.equals(value, ignoreCase = true) || it.name.replace("_", "-").equals(value, ignoreCase = true) }
                ?: throw InvalidOperationException(
                    InvalidOperationExceptionReason.INVALID_ARGUMENT,
                    details = "Unknown sslmode '$value'. Expected one of: ${entries.joinToString(", ") { it.value }}."
                )
        }
    }
}
