package com.smartphonekey.photoframe.gphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerJsonTest {

    @Test
    fun `duration parses seconds and fractions`() {
        assertEquals(5_000L, PickerJson.parseDurationMs("5s", 1L))
        assertEquals(3_500L, PickerJson.parseDurationMs("3.500s", 1L))
        assertEquals(1_800_000L, PickerJson.parseDurationMs("1800s", 1L))
    }

    @Test
    fun `garbage duration falls back to default`() {
        assertEquals(7L, PickerJson.parseDurationMs(null, 7L))
        assertEquals(7L, PickerJson.parseDurationMs("", 7L))
        assertEquals(7L, PickerJson.parseDurationMs("soon", 7L))
        assertEquals(7L, PickerJson.parseDurationMs("-5s", 7L))
    }

    @Test
    fun `session parses id uri polling and flag`() {
        val session = PickerJson.parseSession(
            """{
                "id": "sess-1",
                "pickerUri": "https://photos.google.com/pick/abc",
                "pollingConfig": {"pollInterval": "5s", "timeoutIn": "1800s"},
                "mediaItemsSet": false
            }"""
        )
        assertEquals("sess-1", session.id)
        assertEquals("https://photos.google.com/pick/abc", session.pickerUri)
        assertEquals(5_000L, session.pollIntervalMs)
        assertEquals(1_800_000L, session.timeoutMs)
        assertFalse(session.mediaItemsSet)
    }

    @Test
    fun `polling values are clamped to sane bounds`() {
        // A sub-millisecond interval would hot-loop the poller; an absurd
        // timeout could overflow the deadline arithmetic.
        val session = PickerJson.parseSession(
            """{"id": "s", "pickerUri": "https://photos.google.com/x",
                "pollingConfig": {"pollInterval": "0.0001s", "timeoutIn": "999999999s"}}"""
        )
        assertEquals(PickerJson.MIN_POLL_INTERVAL_MS, session.pollIntervalMs)
        assertEquals(PickerJson.MAX_TIMEOUT_MS, session.timeoutMs)
    }

    @Test
    fun `session without polling config gets defaults`() {
        val session = PickerJson.parseSession("""{"id": "s", "mediaItemsSet": true}""")
        assertEquals(PickerJson.DEFAULT_POLL_INTERVAL_MS, session.pollIntervalMs)
        assertEquals(PickerJson.DEFAULT_TIMEOUT_MS, session.timeoutMs)
        assertTrue(session.mediaItemsSet)
    }

    @Test(expected = org.json.JSONException::class)
    fun `session without an id is rejected`() {
        // A blank id would poll a nonexistent session forever.
        PickerJson.parseSession("""{"pickerUri": "https://photos.google.com/x"}""")
    }

    @Test(expected = org.json.JSONException::class)
    fun `session with an empty id is rejected`() {
        PickerJson.parseSession("""{"id": "  ", "pickerUri": "https://x"}""")
    }

    @Test
    fun `media items page parses items and token`() {
        val page = PickerJson.parseMediaItemsPage(
            """{
                "mediaItems": [
                    {
                        "id": "item-1",
                        "type": "PHOTO",
                        "mediaFile": {
                            "baseUrl": "https://lh3.googleusercontent.com/x",
                            "mimeType": "image/jpeg",
                            "filename": "IMG_001.jpg",
                            "mediaFileMetadata": {"width": 4000, "height": 3000}
                        }
                    },
                    {
                        "id": "item-2",
                        "type": "VIDEO",
                        "mediaFile": {"baseUrl": "https://lh3.googleusercontent.com/v"}
                    }
                ],
                "nextPageToken": "tok"
            }"""
        )
        assertEquals(2, page.items.size)
        assertEquals("tok", page.nextPageToken)
        val photo = page.items[0]
        assertEquals("item-1", photo.id)
        assertEquals(4000, photo.width)
        assertEquals(3000, photo.height)
        assertFalse(photo.isVideo)
        assertTrue(page.items[1].isVideo)
    }

    @Test
    fun `items without baseUrl are skipped and empty page has no token`() {
        val page = PickerJson.parseMediaItemsPage(
            """{"mediaItems": [{"id": "broken", "mediaFile": {}}]}"""
        )
        assertTrue(page.items.isEmpty())
        assertNull(page.nextPageToken)
    }

    @Test(expected = org.json.JSONException::class)
    fun `session with a non-Google pickerUri is rejected`() {
        // Would otherwise be rendered as a QR code for the user to scan.
        PickerJson.parseSession("""{"id": "s", "pickerUri": "https://evil.example/x"}""")
    }

    @Test
    fun `items on a foreign host are dropped before any download`() {
        // baseUrl is fetched with the user's bearer token — never off-host.
        val page = PickerJson.parseMediaItemsPage(
            """{"mediaItems": [
                {"id": "evil", "mediaFile": {"baseUrl": "https://attacker.example/x"}},
                {"id": "ok", "mediaFile": {"baseUrl": "https://lh3.googleusercontent.com/y"}}
            ]}"""
        )
        assertEquals(1, page.items.size)
        assertEquals("ok", page.items[0].id)
    }

    @Test
    fun `items without an id are dropped rather than keyed by url`() {
        val page = PickerJson.parseMediaItemsPage(
            """{"mediaItems": [
                {"mediaFile": {"baseUrl": "https://lh3.googleusercontent.com/y"}}
            ]}"""
        )
        assertTrue(page.items.isEmpty())
    }
}
