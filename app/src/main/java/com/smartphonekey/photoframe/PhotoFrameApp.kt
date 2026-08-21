package com.smartphonekey.photoframe

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import com.smartphonekey.photoframe.gphotos.GPhotosCache
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager
import com.smartphonekey.photoframe.settings.Prefs
import com.smartphonekey.photoframe.source.local.ScanIndexDb
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Composition root. Manual DI on purpose (low-end-performance skill):
 * a handful of singletons, created once, passed by hand.
 */
class PhotoFrameApp : Application() {

    /** Background lane for scans, DB and future downloads. Never the UI. */
    val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    lateinit var prefs: Prefs
        private set
    lateinit var index: ScanIndexDb
        private set
    lateinit var gphotosCache: GPhotosCache
        private set
    lateinit var gphotosSync: GPhotosSyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds log any accidental I/O on the main thread.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
        prefs = Prefs(this)
        index = ScanIndexDb(this)
        gphotosCache = GPhotosCache(this)
        gphotosSync = GPhotosSyncManager(gphotosCache, prefs, ioExecutor)
        // Heal any half-written cache files from a previous crash.
        ioExecutor.execute { gphotosCache.sweep() }
        // Old frames: keep Glide's memory cache small; our two-view slideshow
        // needs almost nothing cached (low-end-performance skill).
        Glide.get(this).setMemoryCategory(MemoryCategory.LOW)
    }
}
