package com.smartphonekey.photoframe.gphotos

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin blocking client for the Photos Picker API. HttpURLConnection on
 * purpose — two endpoints do not justify an HTTP stack on an old frame
 * (low-end-performance skill). Call only from a background executor.
 */
class PickerApi(private val baseUrl: String = "https://photospicker.googleapis.com/v1") {

    class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

    fun createSession(token: String): PickerSession =
        PickerJson.parseSession(request("POST", "$baseUrl/sessions", token, body = "{}"))

    fun getSession(token: String, sessionId: String): PickerSession =
        PickerJson.parseSession(request("GET", "$baseUrl/sessions/$sessionId", token))

    fun listAllMediaItems(token: String, sessionId: String): List<PickedItem> {
        val items = ArrayList<PickedItem>()
        var pageToken: String? = null
        var pages = 0
        do {
            var url = "$baseUrl/mediaItems?sessionId=$sessionId&pageSize=100"
            pageToken?.let { url += "&pageToken=$it" }
            val page = PickerJson.parseMediaItemsPage(request("GET", url, token))
            items.addAll(page.items)
            pageToken = page.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        return items
    }

    fun deleteSession(token: String, sessionId: String) {
        try {
            request("DELETE", "$baseUrl/sessions/$sessionId", token)
        } catch (e: IOException) {
            // Best-effort cleanup; sessions expire on their own anyway.
        }
    }

    /**
     * Downloads media bytes into [target]. Unlike the old Library API,
     * Picker baseUrls REQUIRE the OAuth bearer header.
     * [maxDimension] is applied as `=w{n}-h{n}` — the server fits the image
     * inside that box and transcodes HEIC to JPEG, which is exactly what an
     * old frame needs (google-photos-picker skill).
     */
    fun download(token: String, item: PickedItem, maxDimension: Int, target: File) {
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
        private const val MAX_PAGES = 100 // 10k items — far beyond picker limits
    }
}
