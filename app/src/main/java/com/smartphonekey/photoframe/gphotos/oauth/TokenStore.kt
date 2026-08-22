package com.smartphonekey.photoframe.gphotos.oauth

import android.content.Context

/**
 * Persists the OAuth tokens in app-private SharedPreferences. A photo frame
 * signs in once and must survive years of reboots — the refresh token IS the
 * product feature here. Own prefs file so a settings backup/restore tool
 * that copies the default prefs does not silently move Google credentials;
 * that file is additionally excluded from Auto Backup / device transfer
 * (backup_rules.xml, data_extraction_rules.xml).
 *
 * Deliberately NOT Keystore-encrypted — the accepted threat model:
 * app-private storage already requires root or physical extraction to read,
 * the scope is picker-readonly (only photos the user explicitly picked),
 * and hardware keystores on the target Allwinner-class frames are unreliable
 * enough that the mandatory fallback path would be this same plaintext file.
 * Revisit if the app ever targets hardware with a trustworthy keystore.
 */
class TokenStore(context: Context) {

    private val sp =
        context.applicationContext.getSharedPreferences("gphotos_auth", Context.MODE_PRIVATE)

    var refreshToken: String?
        get() = sp.getString(KEY_REFRESH, null)
        set(value) = sp.edit().putString(KEY_REFRESH, value).apply()

    /** Cached access token, valid until [accessTokenExpiresAt] (epoch ms). */
    fun saveAccessToken(token: String, expiresAtMillis: Long) {
        sp.edit()
            .putString(KEY_ACCESS, token)
            .putLong(KEY_EXPIRES, expiresAtMillis)
            .apply()
    }

    /** Returns the cached access token if it is still comfortably valid. */
    fun validAccessToken(nowMillis: Long): String? {
        val token = sp.getString(KEY_ACCESS, null) ?: return null
        val expiresAt = sp.getLong(KEY_EXPIRES, 0L)
        // 5-minute safety margin: a sync that starts on a nearly-expired
        // token would die mid-download (baseUrls need the bearer header).
        return if (nowMillis + EXPIRY_MARGIN_MS < expiresAt) token else null
    }

    /** Wipes everything — used when the refresh token turns invalid. */
    fun clear() {
        sp.edit().clear().apply()
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS = "access_token"
        const val KEY_EXPIRES = "access_expires_at"
        const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    }
}
