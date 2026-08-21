package com.smartphonekey.photoframe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowIntervalsTest {

    @Test
    fun `presets are sorted and unique`() {
        val presets = SlideshowIntervals.presetsSeconds
        assertTrue("preset list must not be empty", presets.isNotEmpty())
        assertEquals("presets must be sorted ascending", presets.sorted(), presets)
        assertEquals("presets must be unique", presets.distinct().size, presets.size)
    }

    @Test
    fun `default interval is one of the presets`() {
        assertTrue(SlideshowIntervals.isValid(SlideshowIntervals.DEFAULT_SECONDS))
    }

    @Test
    fun `arbitrary value is rejected`() {
        assertTrue(!SlideshowIntervals.isValid(42))
    }
}
