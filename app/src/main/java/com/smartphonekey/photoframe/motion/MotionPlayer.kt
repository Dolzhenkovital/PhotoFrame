package com.smartphonekey.photoframe.motion

import android.content.ContentResolver
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import android.view.TextureView
import com.smartphonekey.photoframe.core.PhotoItem
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min

/**
 * Plays a motion photo's embedded video once, muted, over the still.
 *
 * Zero-copy: MediaPlayer.setDataSource(fd, offset, length) plays the MP4
 * straight out of the JPEG — no extraction, no temp files (motion-photo
 * skill). Rules for old hardware, all enforced here:
 *  - ONE MediaPlayer instance ever: create → play → release before the next.
 *  - Any error (unsupported codec, broken trailer) silently leaves the
 *    still photo on screen — this feature is a garnish, not a meal.
 *  - The TextureView sits above the ImageViews at alpha 0 and only fades in
 *    once the first frames are ready.
 *
 * Main-thread only (MediaPlayer callbacks arrive on the looper thread).
 */
class MotionPlayer(
    private val textureView: TextureView,
    private val resolver: ContentResolver,
    private val ioExecutor: Executor,
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    /** Invalidates async callbacks from a superseded playback. */
    private var playToken = 0

    // The SurfaceTexture appears one layout pass AFTER the view turns
    // VISIBLE — exactly when the first slide after a resume is already on
    // screen. That slide's item waits here and starts the moment the
    // surface arrives; a slide change (stop()) discards it.
    private var pendingItem: PhotoItem? = null
    private var pendingFill = false

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                val item = pendingItem ?: return
                pendingItem = null
                playOnce(item, pendingFill)
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                stop() // playing into a dead surface is pointless
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    /** Starts playback; on any problem the still simply stays visible. */
    fun playOnce(item: PhotoItem, fillScreen: Boolean) {
        stop()
        if (!item.hasMotion) return
        if (!textureView.isAvailable) {
            pendingItem = item
            pendingFill = fillScreen
            return
        }
        val token = ++playToken
        // Opening a SAF descriptor is a synchronous provider IPC — never on
        // the main thread mid-slide. The player itself is main-thread-only,
        // so the descriptor hops back via the handler.
        ioExecutor.execute {
            val pfd = try {
                resolver.openFileDescriptor(Uri.parse(item.uri), "r")
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                if (token != playToken || pfd == null) {
                    pfd?.let { runCatching { it.close() } }
                    return@post
                }
                startPlayback(pfd, item, fillScreen)
            }
        }
    }

    /** Main thread; [pfd] is always closed — MediaPlayer dups it inside
     *  setDataSource, so it is only needed for the duration of this call. */
    private fun startPlayback(pfd: ParcelFileDescriptor, item: PhotoItem, fillScreen: Boolean) {
        val token = playToken
        pfd.use {
            try {
                val texture = textureView.surfaceTexture ?: return
                val mediaPlayer = MediaPlayer()
                player = mediaPlayer
                val videoSurface = Surface(texture)
                surface = videoSurface

                mediaPlayer.setDataSource(
                    it.fileDescriptor, item.videoOffsetBytes, item.videoLengthBytes
                )
                mediaPlayer.setSurface(videoSurface)
                mediaPlayer.setVolume(0f, 0f)
                mediaPlayer.isLooping = false
                mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                    if (token == playToken) applyTransform(width, height, fillScreen)
                }
                mediaPlayer.setOnPreparedListener {
                    if (token != playToken) return@setOnPreparedListener
                    textureView.animate().alpha(1f).setDuration(FADE_MS).start()
                    mediaPlayer.start()
                }
                mediaPlayer.setOnCompletionListener {
                    if (token == playToken) fadeOutAndRelease()
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    if (token == playToken) fadeOutAndRelease()
                    true // handled — never surface a dialog
                }
                mediaPlayer.prepareAsync()
            } catch (e: Exception) {
                stop() // still photo remains — exactly the intended fallback
            }
        }
    }

    /** Immediate teardown (slide change, screen off, settings toggle). */
    fun stop() {
        playToken++
        pendingItem = null
        textureView.animate().cancel()
        textureView.alpha = 0f
        releasePlayer()
    }

    private fun fadeOutAndRelease() {
        val token = playToken
        textureView.animate().alpha(0f).setDuration(FADE_MS)
            .withEndAction { if (token == playToken) releasePlayer() }
            .start()
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    /** Match the still underneath: fit (letterbox) or fill (crop). */
    private fun applyTransform(videoWidth: Int, videoHeight: Int, fillScreen: Boolean) {
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        // TextureView stretches video to the view by default; the transform
        // rescales relative to that stretched state around the center.
        val scale = if (fillScreen) {
            max(viewWidth / videoWidth, viewHeight / videoHeight)
        } else {
            min(viewWidth / videoWidth, viewHeight / videoHeight)
        }
        val matrix = Matrix()
        matrix.setScale(
            videoWidth * scale / viewWidth,
            videoHeight * scale / viewHeight,
            viewWidth / 2f,
            viewHeight / 2f,
        )
        textureView.setTransform(matrix)
    }

    private companion object {
        const val FADE_MS = 150L
    }
}
