package io.github.octaviusframework.driver.auth

/**
 * Gives the bearer token a server asks for under an `oauth` line of `pg_hba.conf`. Set as `oauthTokenProvider` or
 * named in `oauthTokenProviderClassName`, and called at every login that asks, on the thread opening the connection.
 */
fun interface OAuthTokenProvider {
    /** The token for [request], without the `Bearer ` scheme in front of it. */
    fun token(request: OAuthTokenRequest): String
}

/** The login a token is asked for: the server as configured, and the user and database it is logged in to. */
class OAuthTokenRequest internal constructor(
    val host: String,
    val port: Int,
    val user: String,
    val database: String
) {
    override fun toString(): String = "OAuthTokenRequest(host=$host, port=$port, user=$user, database=$database)"
}
