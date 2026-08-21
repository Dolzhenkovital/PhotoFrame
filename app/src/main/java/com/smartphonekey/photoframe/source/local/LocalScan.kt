package com.smartphonekey.photoframe.source.local

import com.smartphonekey.photoframe.core.PhotoItem

/**
 * Rules shared by the two local scanners — [PhotoScanner] (SAF tree) and
 * [MediaStoreScanner] (MediaStore bucket). Pure Kotlin so the JVM tests can
 * cover them without a device.
 */
internal object LocalScan {

    /**
     * Hard cap on indexed photos. A frame that is pointed at a 50k-photo card
     * must still finish scanning and still fit its index in RAM; the queue
     * shuffles anyway, so more would buy nothing.
     */
    const val MAX_PHOTOS = 20_000

    /** Directory recursion limit for the SAF tree walk (local-photos skill). */
    const val MAX_DEPTH = 5

    private val MIMES_BASE = setOf("image/jpeg", "image/png", "image/webp")

    // HEIC/HEIF decoding exists only from API 28 (android-compat skill).
    private val MIMES_WITH_HEIF = MIMES_BASE + setOf("image/heif", "image/heic")

    fun imageMimes(sdkInt: Int): Set<String> =
        if (sdkInt >= 28) MIMES_WITH_HEIF else MIMES_BASE

    /**
     * True when a previously indexed row still describes the file on disk and
     * can be kept without re-opening it — the whole point of the scan index
     * on slow flash.
     *
     * Rows with zero width were never successfully inspected, and rows still
     * carrying [PhotoItem.MOTION_NOT_SCANNED] predate motion detection; both
     * must be re-inspected once even though size and mtime match.
     */
    fun isReusable(cached: PhotoItem?, size: Long, mtime: Long): Boolean =
        cached != null &&
            cached.sizeBytes == size &&
            cached.lastModified == mtime &&
            cached.width > 0 &&
            cached.videoOffsetBytes != PhotoItem.MOTION_NOT_SCANNED
}
