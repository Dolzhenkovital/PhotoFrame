package com.smartphonekey.photoframe.core

/**
 * Preset intervals (in seconds) offered in settings for the slideshow timer.
 * Pure Kotlin on purpose: unit-testable on the JVM without an emulator.
 */
object SlideshowIntervals {

    val presetsSeconds: List<Int> = listOf(5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)

    const val DEFAULT_SECONDS: Int = 30

    fun isValid(value: Int): Boolean = value in presetsSeconds
}
