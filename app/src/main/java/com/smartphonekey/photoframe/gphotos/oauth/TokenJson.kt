package com.smartphonekey.photoframe.gphotos.oauth

import org.json.JSONObject

/**
 * Parses token-endpoint responses. Same rules as PickerJson: the response
 * crosses a network boundary, so required fields are required — a token
 * response without an access_token is an error, not an empty string.
 * Pure org.json, JVM-tested.
 */
object TokenJson {

    /** [refreshToken] is null when Google chose not to rotate it. */
    data class Tokens(
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String?,
    )

    class TokenError(val error: String, description: String?) :
        Exception(if (description.isNullOrBlank()) error else "$error: $description")

    /**
     * @throws TokenError for a structured OAuth error body
     * @throws org.json.JSONException for bodies that are not valid JSON
     */
    fun parse(body: String): Tokens {
        val json = JSONObject(body)
        val error = json.optString("error", "")
        if (error.isNotEmpty()) {
            throw TokenError(error, json.optString("error_description", ""))
        }
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            throw TokenError("invalid_response", "no access_token in response")
        }
        // A missing expires_in would otherwise cache the token forever; one
        // hour is Google's actual value, but expiring "now" is the safe read.
        val expiresIn = json.optLong("expires_in", 0L)
        return Tokens(
            accessToken = accessToken,
            expiresInSeconds = expiresIn,
            refreshToken = json.optString("refresh_token", "").ifEmpty { null },
        )
    }

    /** True when the refresh token itself is dead → re-consent needed. */
    fun isInvalidGrant(e: Exception): Boolean =
        e is TokenError && e.error == "invalid_grant"
}
