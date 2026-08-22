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

    enum class LocalSourceKind { SAF, MEDIA_STORE }

    /**
     * Which local scanner owns the index: the SAF folder pick (primary) or
     * the MediaStore gallery (fallback for firmwares whose SAF provider is
     * broken — see MediaStoreScanner). Follows whichever the user set last.
     */
    var localSourceKind: LocalSourceKind
        get() = if (sp.getString(KEY_LOCAL_KIND, null) == "mediastore") {
            LocalSourceKind.MEDIA_STORE
        } else {
            LocalSourceKind.SAF
        }
        set(value) = sp.edit().putString(
            KEY_LOCAL_KIND,
            if (value == LocalSourceKind.MEDIA_STORE) "mediastore" else "saf"
        ).apply()

    /** MediaStore bucket to scan; null = the whole gallery. */
    var mediaBucketId: Long?
        get() = sp.getString(KEY_BUCKET_ID, null)?.toLongOrNull()
        set(value) = sp.edit().putString(KEY_BUCKET_ID, value?.toString()).apply()

    /** Display name of the chosen bucket, for the settings summary only. */
    var mediaBucketName: String?
        get() = sp.getString(KEY_BUCKET_NAME, null)
        set(value) = sp.edit().putString(KEY_BUCKET_NAME, value).apply()

    /** Open picker-session id, so an unfinished pick can be resumed. */
    var gphotosSessionId: String?
        get() = sp.getString(KEY_GP_SESSION, null)
        set(value) = sp.edit().putString(KEY_GP_SESSION, value).apply()

    val intervalSeconds: Int
        get() = sp.getString(KEY_INTERVAL, null)?.toIntOrNull()
            ?.takeIf(SlideshowIntervals::isValid)
            ?: SlideshowIntervals.DEFAULT_SECONDS

    val transitionEffect: TransitionEffect
        get() = TransitionEffect.fromPref(sp.getString(KEY_TRANSITION, null))

    /** Motion photos: OFF by default — video decode wakes the SoC every
     *  slide, the weak-hardware-safe default (motion-photo skill). */
    val motionPhotosEnabled: Boolean
        get() = sp.getBoolean(KEY_MOTION, false)

    /** Fill & crop instead of letterboxing. Default off: people hate
     *  beheaded relatives (slideshow-engine skill). */
    val fillScreen: Boolean
        get() = sp.getBoolean(KEY_FILL, false)

    val sourceMode: SourceMode
        get() = when (sp.getString(KEY_SOURCE, null)) {
            "local" -> SourceMode.LOCAL
            "google" -> SourceMode.GOOGLE
            else -> SourceMode.BOTH
        }

    /**
     * Google Photos cache cap; default 1 GB (caching.md). Clamped to the
     * advertised preset range — a corrupt backup or hand-edited preference
     * must not be able to disable eviction with an absurd value.
     */
    val cacheSizeBytes: Long
        get() = (sp.getString(KEY_CACHE_SIZE, null)?.toLongOrNull() ?: DEFAULT_CACHE_BYTES)
            .coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_LOCAL_KIND = "local_source_kind"
        const val KEY_BUCKET_ID = "media_bucket_id"
        const val KEY_BUCKET_NAME = "media_bucket_name"
        const val KEY_GP_SESSION = "gp_session_id"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_TRANSITION = "transition_effect"
        const val KEY_SOURCE = "photo_source"
        const val KEY_CACHE_SIZE = "cache_size_bytes"
        const val KEY_MOTION = "motion_photos_enabled"
        const val KEY_FILL = "display_fill"
        const val DEFAULT_CACHE_BYTES = 1_073_741_824L
        const val MIN_CACHE_BYTES = 268_435_456L // 256 MB, smallest preset
        const val MAX_CACHE_BYTES = 4_294_967_296L // 4 GB, largest preset
    }
}
