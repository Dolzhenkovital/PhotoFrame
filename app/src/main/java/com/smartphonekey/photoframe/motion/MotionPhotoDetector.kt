package com.smartphonekey.photoframe.motion

/**
 * Detects an embedded motion-photo video from a JPEG's leading bytes
 * (motion-photo skill). A motion photo is a normal JPEG with an MP4
 * appended at the end; XMP metadata in the head says where it starts:
 *
 *  - Google "MicroVideo" (legacy, Pixel ≤3):
 *    GCamera:MicroVideoOffset = video length in bytes from EOF.
 *  - Google "MotionPhoto v1" (modern Pixels, Samsung since ~2021):
 *    GCamera:MotionPhoto=1 plus a Container:Directory whose video item
 *    (Item:Mime="video/mp4") carries Item:Length, also bytes from EOF.
 *
 * Known limitation, recorded in the skill: legacy Samsung SEF-only files
 * (S7–S10 era, no XMP marker) are not detected — that needs a tail scan
 * per file, too expensive at index time on old frames.
 *
 * Pure Kotlin over plain bytes: the caller reads the head once (the same
 * buffer it uses for bounds/EXIF) and this class never touches I/O, so it
 * unit-tests on the JVM with synthetic fixtures.
 */
object MotionPhotoDetector {

    data class Result(val videoOffsetBytes: Long, val videoLengthBytes: Long)

    /** How much of the file the caller should read: XMP lives in APP1 near
     *  the start; 256 KB covers real-world headers with room to spare. */
    const val HEAD_BYTES = 256 * 1024

    // XMP is XML: values appear either as attributes (name="123") or as
    // element text (<name>123</name>). Both forms exist in the wild.
    private val MICRO_VIDEO_OFFSET =
        Regex("""GCamera:MicroVideoOffset(?:="(\d+)"|>(\d+)<)""")
    private val MOTION_PHOTO_FLAG =
        Regex("""GCamera:MotionPhoto(?:="1"|>1<)""")
    private val VIDEO_MIME =
        Regex("""Item:Mime="video/mp4"""")
    private val ITEM_LENGTH =
        Regex("""Item:Length(?:="(\d+)"|>(\d+)<)""")

    fun detect(head: ByteArray, fileLength: Long): Result? {
        if (fileLength <= 0) return null
        // XMP is ASCII-safe XML embedded in binary; Latin-1 maps every byte
        // 1:1 so the regexes see the markers without charset guessing.
        val text = String(head, Charsets.ISO_8859_1)

        // Modern format first — files can carry both markers for
        // backward compatibility and MotionPhoto v1 is the accurate one.
        if (MOTION_PHOTO_FLAG.containsMatchIn(text) && VIDEO_MIME.containsMatchIn(text)) {
            // The Container:Directory lists the primary image first and the
            // video last; the last Item:Length belongs to the video.
            val length = ITEM_LENGTH.findAll(text).lastOrNull()?.number()
            validate(length, fileLength)?.let { return it }
        }

        val microVideo = MICRO_VIDEO_OFFSET.find(text)?.number()
        return validate(microVideo, fileLength)
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
}
