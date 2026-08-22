package com.smartphonekey.photoframe

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import com.smartphonekey.photoframe.gphotos.GPhotosCache
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager
import com.smartphonekey.photoframe.gphotos.oauth.LoopbackAuth
import com.smartphonekey.photoframe.gphotos.oauth.TokenStore
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
    lateinit var gphotosAuth: LoopbackAuth
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
        gphotosSync = GPhotosSyncManager(
            cache = gphotosCache,
            cacheCapBytes = { prefs.cacheSizeBytes },
            ioExecutor = ioExecutor,
            // Unfinished picks survive polling timeouts and app restarts.
            storeSession = { prefs.gphotosSessionId = it },
            loadStoredSession = { prefs.gphotosSessionId },
        )
        // App-scoped: browser consent may outlive the Settings screen.
        gphotosAuth = LoopbackAuth(
            context = this,
            store = TokenStore(this),
            ioExecutor = ioExecutor,
            clientId = BuildConfig.GP_OAUTH_CLIENT_ID,
            clientSecret = BuildConfig.GP_OAUTH_CLIENT_SECRET,
        )
        // The action taken on a fresh token must not live in an Activity:
        // the user may wander back from the browser minutes after Settings
        // died. Any visible screen re-attaches to the sync's state on
        // resume, so starting it here is enough.
        gphotosAuth.onAccessToken = { token ->
            gphotosSync.begin(token, syncTargetDimension())
        }
        // Heal any half-written cache files from a previous crash.
        ioExecutor.execute { gphotosCache.sweep() }
        // Old frames: keep Glide's memory cache small; our two-view slideshow
        // needs almost nothing cached (low-end-performance skill).
        Glide.get(this).setMemoryCategory(MemoryCategory.LOW)
    }

    /** Screen-fitting download size: enough pixels, never 12MP originals. */
    fun syncTargetDimension(): Int {
        val metrics = resources.displayMetrics
        return maxOf(metrics.widthPixels, metrics.heightPixels).coerceIn(1280, 2048)
    }
}
