package com.smartphonekey.photoframe.motion

/**
 * Detects an embedded motion-photo video from a JPEG's leading bytes
 * (motion-photo skill). A motion photo is a normal JPEG with an MP4
 * appended at the end; XMP metadata in the head says where it starts:
 *
 *  - Google "MicroVideo" (legacy, Pixel ≤3):
 *    GCamera:MicroVideo=1 + GCamera:MicroVideoOffset = video length in
 *    bytes from EOF.
 *  - Google "MotionPhoto v1" (modern Pixels, Samsung since ~2021):
 *    GCamera:MotionPhoto=1 plus a Container:Directory whose video item
 *    (Item:Mime="video/mp4") carries Item:Length, also bytes from EOF.
 *
 * Known limitation, recorded in the skill: legacy Samsung SEF-only files
 * (S7–S10 era, no XMP marker) are not detected — that needs a tail scan
 * per file, too expensive at index time on old frames.
 *
 * Pure Kotlin over plain bytes: the caller reads the head once (the same
 * reusable buffer it uses for bounds/EXIF) and this class never touches
 * I/O, so it unit-tests on the JVM with synthetic fixtures. A cheap byte
 * pre-scan for "GCamera:" avoids building a 256 KB String for the ~99% of
 * photos that are not motion photos — GC churn matters on 1 GB frames.
 */
object MotionPhotoDetector {

    data class Result(val videoOffsetBytes: Long, val videoLengthBytes: Long)

    /** How much of the file the caller should read: XMP lives in APP1 near
     *  the start; 256 KB covers real-world headers with room to spare. */
    const val HEAD_BYTES = 256 * 1024

    private val MARKER = "GCamera:".toByteArray(Charsets.ISO_8859_1)

    // XMP is XML: values appear either as attributes (name="123") or as
    // element text (<name>123</name>). Both forms exist in the wild, and
    // XML legally allows single quotes and whitespace around '=' — the
    // patterns tolerate all of it.
    private val MICRO_VIDEO_FLAG =
        Regex("""GCamera:MicroVideo\s*(?:=\s*["']1["']|>\s*1\s*<)""")
    private val MICRO_VIDEO_OFFSET =
        Regex("""GCamera:MicroVideoOffset\s*(?:=\s*["'](\d+)["']|>\s*(\d+)\s*<)""")
    private val MOTION_PHOTO_FLAG =
        Regex("""GCamera:MotionPhoto\s*(?:=\s*["']1["']|>\s*1\s*<)""")
    private val VIDEO_MIME =
        Regex("""Item:Mime\s*=\s*["']video/mp4["']""")
    private val ITEM_LENGTH =
        Regex("""Item:Length\s*(?:=\s*["'](\d+)["']|>\s*(\d+)\s*<)""")

    fun detect(head: ByteArray, headLength: Int, fileLength: Long): Result? {
        if (fileLength <= 0 || headLength <= 0) return null
        // A pure detector never throws on caller mistakes — an oversized
        // length is clamped to what actually exists in the buffer.
        val length = headLength.coerceAtMost(head.size)
        if (!containsMarker(head, length)) return null
        // XMP is ASCII-safe XML embedded in binary; Latin-1 maps every byte
        // 1:1 so the regexes see the markers without charset guessing.
        val text = String(head, 0, length, Charsets.ISO_8859_1)

        val motionPhotoFlag = MOTION_PHOTO_FLAG.containsMatchIn(text)
        // Modern format first — files can carry both markers for backward
        // compatibility and MotionPhoto v1 is the accurate one.
        if (motionPhotoFlag) {
            validate(v1VideoItemLength(text), fileLength)?.let { return it }
        }

        // MicroVideoOffset counts only when one of the enabling flags is
        // present — a stray/stale offset field alone must not turn a plain
        // photo into a "motion photo" that then fails to play.
        if (motionPhotoFlag || MICRO_VIDEO_FLAG.containsMatchIn(text)) {
            return validate(MICRO_VIDEO_OFFSET.find(text)?.number(), fileLength)
        }
        return null
    }

    /**
     * Returns Item:Length of the Container:Item whose mime is video/mp4 —
     * NOT just any Item:Length in the document: other container items
     * (primary image, gain maps) carry lengths of their own.
     */
    private fun v1VideoItemLength(text: String): Long? {
        var index = text.indexOf("Container:Item")
        while (index >= 0) {
            val next = text.indexOf("Container:Item", index + 1)
            val end = if (next >= 0) next else minOf(text.length, index + 2000)
            val segment = text.substring(index, end)
            if (VIDEO_MIME.containsMatchIn(segment)) {
                return ITEM_LENGTH.find(segment)?.number()
            }
            index = next
        }
        return null
    }

    private fun MatchResult.number(): Long? =
        (groupValues[1].ifEmpty { groupValues[2] }).toLongOrNull()

    /** Both formats express the video length as bytes from EOF. */
    private fun validate(videoLength: Long?, fileLength: Long): Result? {
        if (videoLength == null || videoLength <= 0) return null
        val offset = fileLength - videoLength
        // The video must sit inside the file and cannot start at byte 0 —
        // that would mean "the whole file", i.e. corrupt metadata.
        if (offset <= 0) return null
        return Result(offset, videoLength)
    }

    /** Naive byte search for "GCamera:" — 8-byte needle, effectively O(n). */
    private fun containsMarker(head: ByteArray, headLength: Int): Boolean {
        val limit = headLength - MARKER.size
        outer@ for (i in 0..limit) {
            for (j in MARKER.indices) {
                if (head[i + j] != MARKER[j]) continue@outer
            }
            return true
        }
        return false
    }
}
