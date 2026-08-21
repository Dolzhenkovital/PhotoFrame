package com.smartphonekey.photoframe.settings

import android.content.Context
import androidx.preference.PreferenceManager
import com.smartphonekey.photoframe.core.SlideshowIntervals
import com.smartphonekey.photoframe.slideshow.TransitionEffect

/**
 * Typed access to the app's settings. Keys must match res/xml/preferences.xml.
 */
class Prefs(context: Context) {

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

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_TRANSITION = "transition_effect"
    }
}
