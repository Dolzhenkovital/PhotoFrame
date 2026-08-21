package com.smartphonekey.photoframe.gphotos

import java.net.URI

/**
 * Host validation for URLs that arrive in Picker API responses.
 *
 * Two of them are dangerous if taken on trust:
 *  - `pickerUri` is opened in a browser and rendered as a QR code the user
 *    scans with their phone, so an arbitrary URL becomes a phishing vector.
 *  - `mediaFile.baseUrl` is fetched **with the user's OAuth bearer token**;
 *    pointing it at another host would hand that token to a stranger.
 *
 * Pure Java URI parsing (no android.net.Uri) so this is unit-testable and
 * behaves identically on every API level.
 */
object PickerUris {

    /** Exactly this host — a wildcard over google.com is far too broad. */
    private const val PICKER_HOST = "photos.google.com"

    /** Picker baseUrls are served from lh3/lh4/… .googleusercontent.com. */
    private const val MEDIA_HOST_SUFFIX = ".googleusercontent.com"

    fun isTrustedPickerUri(value: String): Boolean =
        host(value)?.let { it == PICKER_HOST } ?: false

    fun isTrustedMediaBaseUrl(value: String): Boolean =
        host(value)?.let { it.endsWith(MEDIA_HOST_SUFFIX) } ?: false

    /**
     * Returns the lowercase host of an https URL, or null when the value is
     * unusable. Credentials in the authority (`https://photos.google.com@evil`)
     * are rejected outright: they exist only to make a URL look legitimate.
     */
    private fun host(value: String): String? {
        val uri = try {
            URI(value.trim())
        } catch (e: Exception) {
            return null
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.rawAuthority?.contains('@') == true) return null
        return uri.host?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}
