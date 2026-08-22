package com.smartphonekey.photoframe.gphotos.oauth

import java.net.URLEncoder

/**
 * The textual side of the loopback OAuth flow (RFC 8252 §7.3): building the
 * authorization URL, parsing the redirect that lands on 127.0.0.1, and
 * building the token-endpoint bodies. Pure Kotlin, JVM-tested — the socket
 * and HTTP live in LoopbackAuth.
 *
 * Why this flow at all: the frame hardware has no Play services, so the
 * Identity SDK cannot run there. A Desktop-app OAuth client + system browser
 * + loopback redirect works on anything with a browser, needs no APK
 * signature registration, and returns a refresh token — the frame signs in
 * once, forever (see the google-photos-picker skill).
 */
object AuthProtocol {

    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val PICKER_SCOPE =
        "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

    fun redirectUri(port: Int): String = "http://127.0.0.1:$port"

    /**
     * [forceConsent] adds prompt=consent: Google only guarantees a refresh
     * token on a consenting authorization, so it is forced whenever we do
     * not have one stored — an interactive round that came back without a
     * refresh token would otherwise strand the frame with a 1-hour login.
     */
    fun authorizationUrl(
        clientId: String,
        port: Int,
        codeChallenge: String,
        state: String,
        forceConsent: Boolean,
    ): String = buildString {
        append(AUTH_ENDPOINT)
        append("?client_id=").append(enc(clientId))
        append("&redirect_uri=").append(enc(redirectUri(port)))
        append("&response_type=code")
        append("&scope=").append(enc(PICKER_SCOPE))
        append("&code_challenge=").append(enc(codeChallenge))
        append("&code_challenge_method=S256")
        append("&state=").append(enc(state))
        append("&access_type=offline")
        if (forceConsent) append("&prompt=consent")
    }

    /** Outcome of one HTTP request hitting the loopback server. */
    sealed class Redirect {
        /** The OAuth redirect with a valid state. */
        data class Code(val code: String) : Redirect()

        /** The OAuth redirect reporting denial/failure. */
        data class Error(val error: String) : Redirect()

        /** Anything else (favicon probe, wrong state) — keep listening. */
        object Ignore : Redirect()
    }

    /**
     * Parses an HTTP request line ("GET /?code=...&state=... HTTP/1.1")
     * received on the loopback socket. Anything that is not our redirect —
     * including a state mismatch, which could be a CSRF attempt from another
     * local app — is [Redirect.Ignore], not an error: the server keeps
     * waiting for the real redirect until its deadline.
     */
    fun parseRequestLine(requestLine: String, expectedState: String): Redirect {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return Redirect.Ignore
        val query = parts[1].substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return Redirect.Ignore
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            if (key.isNotEmpty() && key !in params) params[key] = urlDecode(value)
        }
        if (params["state"] != expectedState) return Redirect.Ignore
        val error = params["error"]
        if (error != null) return Redirect.Error(error)
        val code = params["code"]
        return if (code.isNullOrEmpty()) Redirect.Ignore else Redirect.Code(code)
    }

    /** Body for exchanging the authorization code (x-www-form-urlencoded). */
    fun codeExchangeBody(
        clientId: String,
        clientSecret: String,
        code: String,
        verifier: String,
        port: Int,
    ): String =
        "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&code_verifier=${enc(verifier)}" +
            "&redirect_uri=${enc(redirectUri(port))}"

    /** Body for refreshing the access token. */
    fun refreshBody(clientId: String, clientSecret: String, refreshToken: String): String =
        "grant_type=refresh_token" +
            "&refresh_token=${enc(refreshToken)}" +
            "&client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun urlDecode(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
        "" // malformed %-escape from a non-OAuth caller — never our redirect
    }
}
