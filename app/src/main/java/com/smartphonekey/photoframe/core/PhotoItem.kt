package com.smartphonekey.photoframe.core

/**
 * One displayable photo. [width]/[height] are visual dimensions with EXIF
 * rotation already applied (0 when unknown). Pure Kotlin — used by the
 * JVM-tested queue logic, so no Android types here.
 */
data class PhotoItem(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int,
) {
    val orientation: PhotoOrientation
        get() = PhotoOrientation.classify(width, height)
}
