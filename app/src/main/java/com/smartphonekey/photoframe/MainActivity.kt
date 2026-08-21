package com.smartphonekey.photoframe

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.smartphonekey.photoframe.core.PhotoOrientation
import com.smartphonekey.photoframe.core.PlaybackQueue
import com.smartphonekey.photoframe.gphotos.GPhotosSyncManager
import com.smartphonekey.photoframe.settings.Prefs
import com.smartphonekey.photoframe.settings.SettingsActivity
import com.smartphonekey.photoframe.slideshow.SlideshowController

class MainActivity : AppCompatActivity(), SlideshowController.Listener {

    private lateinit var app: PhotoFrameApp
    private lateinit var queue: PlaybackQueue
    private lateinit var controller: SlideshowController
    private lateinit var emptyGroup: View
    private lateinit var emptyText: TextView

    // A sync can finish while this screen is already showing (the user
    // closed settings mid-download) — reload so the new photos appear now,
    // not on the next resume. Transitions only: no replay on attach.
    private val syncListener = GPhotosSyncManager.Listener { state ->
        if (state is GPhotosSyncManager.State.Finished && state.added > 0) {
            reloadAndStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as PhotoFrameApp
        // A photo frame must never let the screen sleep while showing photos.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        emptyGroup = findViewById(R.id.empty_group)
        emptyText = findViewById(R.id.empty_text)
        val photoA = findViewById<ImageView>(R.id.photo_a)
        val photoB = findViewById<ImageView>(R.id.photo_b)

        queue = PlaybackQueue()
        controller = SlideshowController(photoA, photoB, app.prefs, queue, this)
        controller.onPhotoShown = { item ->
            app.gphotosCache.noteShown(item.uri, System.currentTimeMillis())
        }

        val openSettings = View.OnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.root).setOnClickListener(openSettings)
        findViewById<View>(R.id.empty_button).setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        app.gphotosSync.attach(syncListener, replay = false)
        reloadAndStart()
    }

    override fun onPause() {
        app.gphotosSync.detach(syncListener)
        controller.stop()
        // Persist the batched last-shown timestamps for LRU eviction.
        app.ioExecutor.execute { app.gphotosCache.flush() }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Manifest opts into configChanges: rotation must not restart the
    // slideshow, only re-prefer the matching photo bucket.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        queue.setDeviceOrientation(deviceOrientation(newConfig))
    }

    override fun onEmpty() {
        val message = if (app.prefs.sourceMode == Prefs.SourceMode.GOOGLE) {
            R.string.empty_no_photos_google
        } else {
            R.string.empty_no_photos
        }
        emptyText.setText(message)
        emptyGroup.visibility = View.VISIBLE
    }

    override fun onAllFailed() {
        emptyText.setText(R.string.error_all_failed)
        emptyGroup.visibility = View.VISIBLE
    }

    private fun reloadAndStart() {
        app.ioExecutor.execute {
            val mode = app.prefs.sourceMode
            val photos = buildList {
                if (mode != Prefs.SourceMode.GOOGLE) addAll(app.index.loadAll())
                if (mode != Prefs.SourceMode.LOCAL) {
                    addAll(app.gphotosCache.loadAllAsPhotoItems())
                }
            }
            runOnUiThread {
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    return@runOnUiThread
                }
                queue.setPhotos(photos)
                queue.setDeviceOrientation(deviceOrientation(resources.configuration))
                controller.stop()
                if (photos.isEmpty()) {
                    onEmpty()
                } else {
                    emptyGroup.visibility = View.GONE
                    controller.start()
                }
            }
        }
    }

    private fun deviceOrientation(config: Configuration): PhotoOrientation =
        if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
            PhotoOrientation.PORTRAIT
        } else {
            PhotoOrientation.LANDSCAPE
        }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
