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

    private fun detect(xmp: String, fileLength: Long): MotionPhotoDetector.Result? {
        val bytes = head(xmp)
        return MotionPhotoDetector.detect(bytes, bytes.size, fileLength)
    }

    @Test
    fun `legacy MicroVideo attribute form`() {
        val result = detect(
            """<x:xmpmeta GCamera:MicroVideo="1" GCamera:MicroVideoOffset="4000"/>""",
            fileLength = 10_000,
        )!!
        assertEquals(6_000L, result.videoOffsetBytes)
        assertEquals(4_000L, result.videoLengthBytes)
    }

    @Test
    fun `legacy MicroVideo element form`() {
        val result = detect(
            "<GCamera:MicroVideo>1</GCamera:MicroVideo>" +
                "<GCamera:MicroVideoOffset>2500</GCamera:MicroVideoOffset>",
            fileLength = 10_000,
        )!!
        assertEquals(7_500L, result.videoOffsetBytes)
    }

    @Test
    fun `offset without any enabling flag is a stale field, not a motion photo`() {
        assertNull(detect("""GCamera:MicroVideoOffset="4000"""", fileLength = 10_000))
    }

    @Test
    fun `MotionPhoto v1 takes the length of the VIDEO item specifically`() {
        val xmp = """
            <rdf:Description GCamera:MotionPhoto="1">
              <Container:Directory>
                <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
                <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="3000"/>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val result = detect(xmp, fileLength = 10_000)!!
        assertEquals(7_000L, result.videoOffsetBytes)
        assertEquals(3_000L, result.videoLengthBytes)
    }

    @Test
    fun `v1 ignores non-video item lengths that come after the video item`() {
        // A gain-map (or any extra) item after the video must not shadow it.
        val xmp = """
            <rdf:Description GCamera:MotionPhoto="1">
              <Container:Directory>
                <Container:Item Item:Mime="video/mp4" Item:Length="3000"/>
                <Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="9999"/>
              </Container:Directory>
            </rdf:Description>
        """.trimIndent()
        val result = detect(xmp, fileLength = 10_000)!!
        assertEquals(3_000L, result.videoLengthBytes)
        assertEquals(7_000L, result.videoOffsetBytes)
    }

    @Test
    fun `v1 flag without a video item falls back to MicroVideo offset`() {
        val result = detect(
            """GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="1000"""",
            fileLength = 5_000,
        )!!
        assertEquals(4_000L, result.videoOffsetBytes)
    }

    @Test
    fun `single quotes and whitespace around equals are legal XML`() {
        val result = detect(
            """<rdf:Description GCamera:MotionPhoto = '1'>
               <Container:Item Item:Mime = 'video/mp4' Item:Length = '3000'/>""",
            fileLength = 10_000,
        )!!
        assertEquals(7_000L, result.videoOffsetBytes)
    }

    @Test
    fun `plain photo yields null`() {
        assertNull(detect("<x:xmpmeta xmlns:x=\"adobe\"/>", 10_000))
        assertNull(MotionPhotoDetector.detect(ByteArray(64), 64, 10_000))
    }

    @Test
    fun `oversized headLength is clamped, not thrown`() {
        val bytes = head("""GCamera:MicroVideo="1" GCamera:MicroVideoOffset="4000"""")
        val result = MotionPhotoDetector.detect(bytes, bytes.size + 500, 10_000)!!
        assertEquals(6_000L, result.videoOffsetBytes)
        assertNull(MotionPhotoDetector.detect(ByteArray(8), 9_999, 10_000))
    }

    @Test
    fun `corrupt metadata is rejected`() {
        val flagged = """GCamera:MicroVideo="1" GCamera:MicroVideoOffset="""
        // Video "longer" than the file itself.
        assertNull(detect(flagged + "\"20000\"", fileLength = 10_000))
        // Zero-length video.
        assertNull(detect(flagged + "\"0\"", fileLength = 10_000))
        // Video length equal to whole file (offset 0 = corrupt).
        assertNull(detect(flagged + "\"10000\"", fileLength = 10_000))
    }
}
