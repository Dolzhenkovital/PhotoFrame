package com.smartphonekey.photoframe.source.local

import com.smartphonekey.photoframe.core.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanTest {

    private fun item(
        size: Long = 100L,
        mtime: Long = 1_000L,
        width: Int = 800,
        videoOffset: Long = -1L,
    ) = PhotoItem(
        uri = "content://x/1",
        displayName = "a.jpg",
        sizeBytes = size,
        lastModified = mtime,
        width = width,
        height = 600,
        videoOffsetBytes = videoOffset,
    )

    @Test
    fun `unchanged inspected row is reused`() {
        assertTrue(LocalScan.isReusable(item(), size = 100L, mtime = 1_000L))
    }

    @Test
    fun `size or mtime change forces re-inspection`() {
        assertFalse(LocalScan.isReusable(item(), size = 101L, mtime = 1_000L))
        assertFalse(LocalScan.isReusable(item(), size = 100L, mtime = 2_000L))
    }

    @Test
    fun `missing row is not reusable`() {
        assertFalse(LocalScan.isReusable(null, size = 100L, mtime = 1_000L))
    }

    @Test
    fun `row that never yielded dimensions is retried`() {
        assertFalse(LocalScan.isReusable(item(width = 0), size = 100L, mtime = 1_000L))
    }

    @Test
    fun `pre-motion-migration row is re-inspected once`() {
        assertFalse(
            LocalScan.isReusable(
                item(videoOffset = PhotoItem.MOTION_NOT_SCANNED),
                size = 100L,
                mtime = 1_000L,
            )
        )
    }

    @Test
    fun `heif accepted only from api 28`() {
        assertFalse("image/heic" in LocalScan.imageMimes(23))
        assertTrue("image/heic" in LocalScan.imageMimes(28))
        assertTrue("image/jpeg" in LocalScan.imageMimes(23))
    }

    @Test
    fun `stale MediaStore rows are dropped, decodable ones kept`() {
        // Zero dimensions after inspection = stale row (file deleted behind
        // the index) — indexing it would break the slideshow and waste a
        // MAX_PHOTOS slot.
        assertFalse(LocalScan.keepMediaStoreItem(item(width = 0)))
        assertTrue(LocalScan.keepMediaStoreItem(item(width = 800)))
    }

    @Test
    fun `base mimes are the three decodable-everywhere formats`() {
        assertEquals(
            setOf("image/jpeg", "image/png", "image/webp"),
            LocalScan.imageMimes(23),
        )
    }
}
