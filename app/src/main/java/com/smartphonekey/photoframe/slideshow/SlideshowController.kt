package com.smartphonekey.photoframe.slideshow

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.smartphonekey.photoframe.core.PhotoItem
import com.smartphonekey.photoframe.core.PlaybackQueue
import com.smartphonekey.photoframe.settings.Prefs
import kotlin.random.Random

/**
 * The slideshow state machine (slideshow-engine skill):
 * preload next into the hidden view → timer tick starts a transition →
 * views swap roles → preload the following photo. A timer tick never does
 * I/O; if decode is slower than the interval, the switch fires when the
 * decode lands.
 */
class SlideshowController(
    private val viewA: ImageView,
    private val viewB: ImageView,
    private val prefs: Prefs,
    private val queue: PlaybackQueue,
    private val listener: Listener,
) {

    interface Listener {
        /** Queue has no photos at all. */
        fun onEmpty()

        /** Every photo in the queue failed to decode. */
        fun onAllFailed()
    }

    private val context: Context = viewA.context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val random = Random.Default
    private val advanceRunnable = Runnable { advance() }

    private var front: ImageView = viewA
    private var back: ImageView = viewB
    private var frontItem: PhotoItem? = null
    private var backItem: PhotoItem? = null
    private var running = false
    private var nextReady = false
    private var pendingAdvance = false
    private var consecutiveFailures = 0
    private var lastEffect: TransitionEffect? = null
    private var driftingView: ImageView? = null

    /** Invoked on the main thread each time a photo lands on screen. */
    var onPhotoShown: ((PhotoItem) -> Unit)? = null

    fun start() {
        if (running) return
        if (queue.size == 0) {
            listener.onEmpty()
            return
        }
        running = true
        nextReady = false
        consecutiveFailures = 0
        // First photo goes through the normal advance path: the "outgoing"
        // view is just invisible, so the effect plays against black.
        pendingAdvance = true
        preloadNext()
    }

    fun stop() {
        running = false
        pendingAdvance = false
        nextReady = false
        handler.removeCallbacksAndMessages(null)
        stopDrift(reset = true)
        Transitions.resetProperties(viewA)
        Transitions.resetProperties(viewB)
    }

    private fun preloadNext() {
        nextReady = false
        val item = queue.next() ?: return
        backItem = item
        val metrics = back.resources.displayMetrics
        val options = RequestOptions()
            // RGB_565 + screen-size decode + no duplicate disk cache:
            // the entire bitmap budget lives here (low-end-performance skill).
            .format(DecodeFormat.PREFER_RGB_565)
            .downsample(DownsampleStrategy.AT_MOST)
            .override(metrics.widthPixels, metrics.heightPixels)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
        Glide.with(back)
            .load(Uri.parse(item.uri))
            .apply(options)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    handler.post { onPreloadDone(false) }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    handler.post { onPreloadDone(true) }
                    return false
                }
            })
            .into(back)
    }

    private fun onPreloadDone(success: Boolean) {
        if (!running) return
        if (!success) {
            consecutiveFailures++
            if (consecutiveFailures >= maxOf(queue.size, 3)) {
                listener.onAllFailed()
                stop()
            } else {
                preloadNext() // skip the broken file, try the next one
            }
            return
        }
        consecutiveFailures = 0
        nextReady = true
        if (pendingAdvance) {
            pendingAdvance = false
            advance()
        }
    }

    private fun advance() {
        if (!running) return
        if (!nextReady) {
            // Decode still in flight — switch as soon as it lands.
            pendingAdvance = true
            return
        }
        nextReady = false
        stopDrift(reset = false) // transition's finish() resets properties
        val effect = TransitionEffect.resolve(prefs.transitionEffect, lastEffect, random)
        lastEffect = effect
        val outgoing = front
        val incoming = back
        Transitions.run(effect, outgoing, incoming) {
            if (!running) return@run
            front = incoming
            back = outgoing
            frontItem = backItem
            backItem = null
            afterShown()
        }
    }

    private fun afterShown() {
        frontItem?.let { shown -> onPhotoShown?.invoke(shown) }
        maybeStartDrift(front)
        if (queue.size > 1) {
            preloadNext()
            handler.removeCallbacks(advanceRunnable)
            handler.postDelayed(advanceRunnable, prefs.intervalSeconds * 1000L)
        }
    }

    /** Ken Burns: slow zoom drift over the whole display interval. */
    private fun maybeStartDrift(view: ImageView) {
        if (prefs.transitionEffect != TransitionEffect.KEN_BURNS) return
        if (prefs.intervalSeconds > 60) return // imperceptible when too slow
        if (isPowerSave()) return
        driftingView = view
        view.pivotX = view.width * (0.3f + random.nextFloat() * 0.4f)
        view.pivotY = view.height * (0.3f + random.nextFloat() * 0.4f)
        view.animate().scaleX(1.08f).scaleY(1.08f)
            .setDuration(prefs.intervalSeconds * 1000L)
            .start()
    }

    private fun stopDrift(reset: Boolean) {
        driftingView?.let { view ->
            view.animate().cancel()
            if (reset) {
                view.scaleX = 1f
                view.scaleY = 1f
            }
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        driftingView = null
    }

    private fun isPowerSave(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode == true
    }
}
