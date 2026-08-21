package com.smartphonekey.photoframe.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoDetectorTest {

    private fun head(xmp: String): ByteArray {
        // Simulates a JPEG head: binary-ish prefix, XMP in the middle.
        val prefix = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
        return prefix + xmp.toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun `legacy MicroVideo attribute form`() {
        val result = MotionPhotoDetector.detect(
            head("""<x:xmpmeta GCamera:MicroVideo="1" GCamera:MicroVideoOffset="4000"/>"""),
            fileLength = 10_000,
        )!!
        assertEquals(6_000L, result.videoOffsetBytes)
        assertEquals(4_000L, result.videoLengthBytes)
    }

    @Test
    fun `legacy MicroVideo element form`() {
        val result = MotionPhotoDetector.detect(
            head("<GCamera:MicroVideoOffset>2500</GCamera:MicroVideoOffset>"),
            fileLength = 10_000,
        )!!
        assertEquals(7_500L, result.videoOffsetBytes)
    }

    @Test
    fun `MotionPhoto v1 uses the last Item Length (the video item)`() {
        val xmp = """
            <rdf:Description GCamera:MotionPhoto="1">
              <Container:Directory>
                <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="3000"/>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val result = MotionPhotoDetector.detect(head(xmp), fileLength = 10_000)!!
        assertEquals(7_000L, result.videoOffsetBytes)
        assertEquals(3_000L, result.videoLengthBytes)
    }

    @Test
    fun `v1 flag without a video item falls back to MicroVideo marker if present`() {
        val xmp = """GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="1000""""
        val result = MotionPhotoDetector.detect(head(xmp), fileLength = 5_000)!!
        assertEquals(4_000L, result.videoOffsetBytes)
    }

    @Test
    fun `plain photo yields null`() {
        assertNull(MotionPhotoDetector.detect(head("<x:xmpmeta xmlns:x=\"adobe\"/>"), 10_000))
        assertNull(MotionPhotoDetector.detect(ByteArray(64), 10_000))
    }

    @Test
    fun `corrupt metadata is rejected`() {
        // Video "longer" than the file itself.
        assertNull(
            MotionPhotoDetector.detect(
                head("""GCamera:MicroVideoOffset="20000""""),
                fileLength = 10_000,
            )
        )
        // Zero-length video.
        assertNull(
            MotionPhotoDetector.detect(
                head("""GCamera:MicroVideoOffset="0""""),
                fileLength = 10_000,
            )
        )
        // Video length equal to whole file (offset 0 = corrupt).
        assertNull(
            MotionPhotoDetector.detect(
                head("""GCamera:MicroVideoOffset="10000""""),
                fileLength = 10_000,
            )
        )
    }
}
