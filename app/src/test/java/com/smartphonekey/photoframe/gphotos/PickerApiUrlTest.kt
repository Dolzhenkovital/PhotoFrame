package com.smartphonekey.photoframe.gphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerApiUrlTest {

    private val api = PickerApi(baseUrl = "https://example.test/v1")

    @Test
    fun `page token special characters are percent-encoded`() {
        // Real page tokens are base64-ish: raw '+' would arrive as a space.
        val url = api.mediaItemsUrl("sess-1", "aB+c/d=e")
        assertTrue(url.endsWith("&pageToken=aB%2Bc%2Fd%3De"))
        assertFalse(url.contains("aB+c"))
    }

    @Test
    fun `spaces become percent20 not plus`() {
        assertEquals("a%20b", PickerApi.encode("a b"))
    }

    @Test
    fun `no page token means no parameter`() {
        val url = api.mediaItemsUrl("sess-1", null)
        assertEquals("https://example.test/v1/mediaItems?sessionId=sess-1&pageSize=100", url)
    }

    @Test
    fun `session id is encoded too`() {
        assertTrue(api.mediaItemsUrl("a/b", null).contains("sessionId=a%2Fb"))
    }
}
