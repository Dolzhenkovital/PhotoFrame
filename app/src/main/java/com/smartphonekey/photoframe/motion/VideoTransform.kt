package com.smartphonekey.photoframe.motion

import kotlin.math.max
import kotlin.math.min

/**
 * Scale factors for rendering a video inside a TextureView, which stretches
 * the video to the view bounds by default. The returned (sx, sy) are applied
 * as a Matrix scale around the view center, producing fit (letterbox) or
 * fill (crop) — matching whatever the still underneath uses.
 *
 * Pure Kotlin: transform math is trivially easy to regress and highly
 * visible on a fixed-aspect frame, so it lives where the JVM can test it.
 */
object VideoTransform {

    data class Scale(val sx: Float, val sy: Float)

    fun compute(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
        fillScreen: Boolean,
    ): Scale? {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return null
        }
        val scale = if (fillScreen) {
            max(viewWidth / videoWidth, viewHeight / videoHeight)
        } else {
            min(viewWidth / videoWidth, viewHeight / videoHeight)
        }
        return Scale(
            sx = videoWidth * scale / viewWidth,
            sy = videoHeight * scale / viewHeight,
        )
    }
}
