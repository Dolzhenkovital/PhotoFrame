package com.smartphonekey.photoframe.gphotos

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin blocking client for the Photos Picker API. HttpURLConnection on
 * purpose — two endpoints do not justify an HTTP stack on an old frame
 * (low-end-performance skill). Call only from a background executor.
 */
class PickerApi(
    private val baseUrl: String = "https://photospicker.googleapis.com/v1",
) : PickerClient {

    class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

    override fun createSession(token: String): PickerSession {
        val session =
            PickerJson.parseSession(request("POST", "$baseUrl/sessions", token, body = "{}"))
        // Without a pickerUri there is nothing to show as a QR code and the
        // user could never pick anything — fail loudly instead of leaving the
        // sync waiting forever on a blank dialog.
        if (session.pickerUri.isBlank()) {
            throw IOException("Picker session has no pickerUri")
        }
        return session
    }

    override fun getSession(token: String, sessionId: String): PickerSession =
        PickerJson.parseSession(request("GET", sessionUrl(sessionId), token))

    override fun listAllMediaItems(token: String, sessionId: String): List<PickedItem> {
        val items = ArrayList<PickedItem>()
        var pageToken: String? = null
        var pages = 0
        do {
            val page = PickerJson.parseMediaItemsPage(
                request("GET", mediaItemsUrl(sessionId, pageToken), token)
            )
            items.addAll(page.items)
            pageToken = page.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        return items
    }

    override fun deleteSession(token: String, sessionId: String) {
        try {
            request("DELETE", sessionUrl(sessionId), token)
        } catch (e: IOException) {
            // Best-effort cleanup; sessions expire on their own anyway. Still
            // worth a log line: repeated failures here mean leaked sessions.
            Log.w(TAG, "Failed to delete picker session: ${e.message}")
        }
    }

    /**
     * Downloads media bytes into [target]. Unlike the old Library API,
     * Picker baseUrls REQUIRE the OAuth bearer header.
     * [maxDimension] is applied as `=w{n}-h{n}` — the server fits the image
     * inside that box and transcodes HEIC to JPEG, which is exactly what an
     * old frame needs (google-photos-picker skill).
     */
    override fun download(token: String, item: PickedItem, maxDimension: Int, target: File) {
        val url = "${item.baseUrl}=w$maxDimension-h$maxDimension"
        val conn = open("GET", url, token)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(code, readError(conn))
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, url: String, token: String, body: String? = null): String {
        val conn = open(method, url, token)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw ApiException(code, readError(conn))
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun readError(conn: HttpURLConnection): String = try {
        conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.take(500) ?: "(no error body)"
    } catch (e: IOException) {
        "(unreadable error body)"
    }

    companion object {
        private const val TAG = "PickerApi"
        private const val MAX_PAGES = 100 // 10k items — far beyond picker limits

        /**
         * Page tokens are opaque base64-ish strings that routinely contain
         * `+`, `/` and `=`; pasted raw into a query string they would be
         * misread by the server (`+` becomes a space). Session ids get the
         * same treatment so a stray character can never break the path.
         */
        fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun sessionUrl(sessionId: String) = "$baseUrl/sessions/${encode(sessionId)}"

    internal fun mediaItemsUrl(sessionId: String, pageToken: String?): String {
        var url = "$baseUrl/mediaItems?sessionId=${encode(sessionId)}&pageSize=100"
        if (pageToken != null) url += "&pageToken=${encode(pageToken)}"
        return url
    }
}
