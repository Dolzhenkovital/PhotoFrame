package com.smartphonekey.photoframe.settings

import android.content.Context
import androidx.preference.PreferenceManager
import com.smartphonekey.photoframe.core.SlideshowIntervals
import com.smartphonekey.photoframe.slideshow.TransitionEffect

/**
 * Typed access to the app's settings. Keys must match res/xml/preferences.xml.
 */
class Prefs(context: Context) {

    enum class SourceMode { BOTH, LOCAL, GOOGLE }

    private val sp = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /** SAF tree URI of the chosen photo folder, null until picked. */
    var folderUri: String?
        get() = sp.getString(KEY_FOLDER_URI, null)
        set(value) = sp.edit().putString(KEY_FOLDER_URI, value).apply()

    val intervalSeconds: Int
        get() = sp.getString(KEY_INTERVAL, null)?.toIntOrNull()
            ?.takeIf(SlideshowIntervals::isValid)
            ?: SlideshowIntervals.DEFAULT_SECONDS

    val transitionEffect: TransitionEffect
        get() = TransitionEffect.fromPref(sp.getString(KEY_TRANSITION, null))

    val sourceMode: SourceMode
        get() = when (sp.getString(KEY_SOURCE, null)) {
            "local" -> SourceMode.LOCAL
            "google" -> SourceMode.GOOGLE
            else -> SourceMode.BOTH
        }

    /** Google Photos cache cap; default 1 GB (caching.md). */
    val cacheSizeBytes: Long
        get() = sp.getString(KEY_CACHE_SIZE, null)?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_CACHE_BYTES

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_TRANSITION = "transition_effect"
        const val KEY_SOURCE = "photo_source"
        const val KEY_CACHE_SIZE = "cache_size_bytes"
        const val DEFAULT_CACHE_BYTES = 1_073_741_824L
    }
}
