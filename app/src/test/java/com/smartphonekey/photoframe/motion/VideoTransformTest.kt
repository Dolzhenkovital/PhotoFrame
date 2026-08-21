package com.smartphonekey.photoframe.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTransformTest {

    private val epsilon = 0.0001f

    @Test
    fun `landscape video on landscape frame in fit mode letterboxes vertically`() {
        // 16:9 video on a 16:10 (1280x800) frame: full width, bars top/bottom.
        val scale = VideoTransform.compute(1280f, 800f, 1920f, 1080f, fillScreen = false)!!
        assertEquals(1f, scale.sx, epsilon)
        assertEquals(0.9f, scale.sy, epsilon)
    }

    @Test
    fun `portrait video on landscape frame in fit mode pillarboxes`() {
        // 9:16 video on 1280x800: full height, narrow pillar in the middle.
        val scale = VideoTransform.compute(1280f, 800f, 1080f, 1920f, fillScreen = false)!!
        assertEquals(0.3516f, scale.sx, 0.001f) // (1080 * 800/1920) / 1280
        assertEquals(1f, scale.sy, epsilon)
    }

    @Test
    fun `fill mode covers the whole view and crops the long side`() {
        val scale = VideoTransform.compute(1280f, 800f, 1920f, 1080f, fillScreen = true)!!
        // Height governs: scale = 800/1080; width overflows past the view.
        assertEquals(1.1111f, scale.sx, 0.001f)
        assertEquals(1f, scale.sy, epsilon)
    }

    @Test
    fun `portrait frame with landscape video fits by width`() {
        val scale = VideoTransform.compute(800f, 1280f, 1920f, 1080f, fillScreen = false)!!
        assertEquals(1f, scale.sx, epsilon)
        assertEquals(0.3516f, scale.sy, 0.001f) // (1080 * 800/1920) / 1280
    }

    @Test
    fun `matching aspect is identity in both modes`() {
        val fit = VideoTransform.compute(1280f, 800f, 1600f, 1000f, fillScreen = false)!!
        val fill = VideoTransform.compute(1280f, 800f, 1600f, 1000f, fillScreen = true)!!
        assertEquals(1f, fit.sx, epsilon)
        assertEquals(1f, fit.sy, epsilon)
        assertEquals(1f, fill.sx, epsilon)
        assertEquals(1f, fill.sy, epsilon)
    }

    @Test
    fun `degenerate dimensions yield null instead of NaN transforms`() {
        assertNull(VideoTransform.compute(0f, 800f, 1920f, 1080f, false))
        assertNull(VideoTransform.compute(1280f, 800f, 0f, 1080f, false))
        assertNull(VideoTransform.compute(1280f, 800f, 1920f, -1f, true))
    }
}
