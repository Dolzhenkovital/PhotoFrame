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
        return PickerSession(
            id = id,
            pickerUri = json.optString("pickerUri", ""),
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
                if (baseUrl.isEmpty()) continue
                val meta = mediaFile.optJSONObject("mediaFileMetadata")
                items.add(
                    PickedItem(
                        id = item.optString("id", baseUrl),
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
