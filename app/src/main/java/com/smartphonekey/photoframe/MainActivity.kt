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
import com.smartphonekey.photoframe.settings.SettingsActivity
import com.smartphonekey.photoframe.slideshow.SlideshowController

class MainActivity : AppCompatActivity(), SlideshowController.Listener {

    private lateinit var app: PhotoFrameApp
    private lateinit var queue: PlaybackQueue
    private lateinit var controller: SlideshowController
    private lateinit var emptyGroup: View
    private lateinit var emptyText: TextView

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

        val openSettings = View.OnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.root).setOnClickListener(openSettings)
        findViewById<View>(R.id.empty_button).setOnClickListener(openSettings)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        reloadAndStart()
    }

    override fun onPause() {
        controller.stop()
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
        emptyText.setText(R.string.empty_no_photos)
        emptyGroup.visibility = View.VISIBLE
    }

    override fun onAllFailed() {
        emptyText.setText(R.string.error_all_failed)
        emptyGroup.visibility = View.VISIBLE
    }

    private fun reloadAndStart() {
        app.ioExecutor.execute {
            val photos = app.index.loadAll()
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
