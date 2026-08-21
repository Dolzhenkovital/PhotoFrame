package com.smartphonekey.photoframe.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoOrientationTest {

    @Test
    fun `tall photo is portrait`() {
        assertEquals(PhotoOrientation.PORTRAIT, PhotoOrientation.classify(89, 100))
        assertEquals(PhotoOrientation.PORTRAIT, PhotoOrientation.classify(3000, 4000))
    }

    @Test
    fun `wide photo is landscape`() {
        assertEquals(PhotoOrientation.LANDSCAPE, PhotoOrientation.classify(112, 100))
        assertEquals(PhotoOrientation.LANDSCAPE, PhotoOrientation.classify(4000, 3000))
    }

    @Test
    fun `near-square photo is square`() {
        assertEquals(PhotoOrientation.SQUARE, PhotoOrientation.classify(100, 100))
        assertEquals(PhotoOrientation.SQUARE, PhotoOrientation.classify(95, 100))
        assertEquals(PhotoOrientation.SQUARE, PhotoOrientation.classify(105, 100))
    }

    @Test
    fun `unknown dimensions are square so the photo is never starved`() {
        assertEquals(PhotoOrientation.SQUARE, PhotoOrientation.classify(0, 0))
        assertEquals(PhotoOrientation.SQUARE, PhotoOrientation.classify(-1, 100))
    }
}
