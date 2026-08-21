package com.smartphonekey.photoframe.core

/**
 * One displayable photo. [width]/[height] are visual dimensions with EXIF
 * rotation already applied (0 when unknown). Pure Kotlin — used by the
 * JVM-tested queue logic, so no Android types here.
 *
 * [videoOffsetBytes]/[videoLengthBytes] describe an embedded motion-photo
 * video (bytes from the start of the file); -1/0 when there is none. The
 * defaults keep every non-motion call site unchanged.
 */
data class PhotoItem(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int,
    val videoOffsetBytes: Long = -1L,
    val videoLengthBytes: Long = 0L,
) {
    val orientation: PhotoOrientation
        get() = PhotoOrientation.classify(width, height)

    val hasMotion: Boolean
        get() = videoOffsetBytes >= 0 && videoLengthBytes > 0
}
