package com.smartphonekey.photoframe.gphotos

import org.json.JSONException
import org.json.JSONObject

/**
 * JSON parsing for Picker API responses, kept free of Android/HTTP types so
 * it unit-tests on the JVM (the real org.json artifact is a test dependency).
 * Tolerant by design: missing optional fields degrade to defaults, they must
 * not kill a sync.
 */
object PickerJson {

    const val DEFAULT_POLL_INTERVAL_MS = 5_000L
    const val DEFAULT_TIMEOUT_MS = 30 * 60_000L

    /** Parses protobuf JSON Duration ("5s", "3.500s") into milliseconds. */
    fun parseDurationMs(raw: String?, defaultMs: Long): Long {
        if (raw.isNullOrBlank()) return defaultMs
        val seconds = raw.trim().removeSuffix("s").toDoubleOrNull() ?: return defaultMs
        if (seconds <= 0) return defaultMs
        return (seconds * 1000).toLong()
    }

    fun parseSession(body: String): PickerSession {
        val json = JSONObject(body)
        val polling = json.optJSONObject("pollingConfig")
        val id = json.optString("id")
        // Everything downstream keys off the session id; an empty one would
        // silently produce a sync that polls a nonexistent session.
        if (id.isBlank()) throw JSONException("session id is missing")
        val pickerUri = json.optString("pickerUri", "")
        // Validated here, at the boundary — the URI is both opened in a
        // browser and rendered as a QR code for the user's phone, so every
        // consumer must be able to assume it is a real Google Photos link.
        if (pickerUri.isNotBlank() && !PickerUris.isTrustedPickerUri(pickerUri)) {
            throw JSONException("pickerUri is not a Google Photos URL")
        }
        return PickerSession(
            id = id,
            pickerUri = pickerUri,
            pollIntervalMs = parseDurationMs(
                polling?.optString("pollInterval"), DEFAULT_POLL_INTERVAL_MS
            ),
            timeoutMs = parseDurationMs(
                polling?.optString("timeoutIn"), DEFAULT_TIMEOUT_MS
            ),
            mediaItemsSet = json.optBoolean("mediaItemsSet", false),
        )
    }

    fun parseMediaItemsPage(body: String): PickedPage {
        val json = JSONObject(body)
        val items = ArrayList<PickedItem>()
        val array = json.optJSONArray("mediaItems")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val mediaFile = item.optJSONObject("mediaFile") ?: continue
                val baseUrl = mediaFile.optString("baseUrl", "")
                // baseUrl is fetched with the user's bearer token, so an item
                // pointing anywhere but Google's media hosts is dropped rather
                // than downloaded. A missing id is dropped too: it keys the
                // cache, and falling back to the URL would let one item
                // masquerade as another.
                val id = item.optString("id", "")
                if (id.isBlank() || !PickerUris.isTrustedMediaBaseUrl(baseUrl)) continue
                val meta = mediaFile.optJSONObject("mediaFileMetadata")
                items.add(
                    PickedItem(
                        id = id,
                        filename = mediaFile.optString("filename", ""),
                        mimeType = mediaFile.optString("mimeType", "image/jpeg"),
                        baseUrl = baseUrl,
                        isVideo = item.optString("type") == "VIDEO",
                        width = meta?.optInt("width", 0) ?: 0,
                        height = meta?.optInt("height", 0) ?: 0,
                    )
                )
            }
        }
        val token = json.optString("nextPageToken", "")
        return PickedPage(items, token.ifEmpty { null })
    }
}
