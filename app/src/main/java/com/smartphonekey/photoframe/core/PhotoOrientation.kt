package com.smartphonekey.photoframe.core

/**
 * Aspect-ratio class of a photo (or of the device screen).
 * Thresholds per the slideshow-engine skill: near-square photos fit both
 * device orientations, so they get their own bucket.
 */
enum class PhotoOrientation {
    PORTRAIT,
    SQUARE,
    LANDSCAPE;

    companion object {
        /**
         * Classify by visual (post-EXIF-rotation) dimensions.
         * Unknown dimensions (0/negative) classify as SQUARE so the photo is
         * never starved out of either orientation's queue.
         */
        fun classify(width: Int, height: Int): PhotoOrientation {
            if (width <= 0 || height <= 0) return SQUARE
            val ratio = width.toFloat() / height.toFloat()
            return when {
                ratio < 0.9f -> PORTRAIT
                ratio > 1.1f -> LANDSCAPE
                else -> SQUARE
            }
        }
    }
}
