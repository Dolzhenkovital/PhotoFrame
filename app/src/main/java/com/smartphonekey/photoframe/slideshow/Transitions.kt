package com.smartphonekey.photoframe.slideshow

import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView

/**
 * Transition implementations per the slideshow-engine skill catalog.
 *
 * Contract: animate outgoing/incoming, call onEnd exactly once, and leave
 * both views with neutral properties (alpha 1, translation 0, scale 1,
 * rotation 0) with only `incoming` visible. ViewPropertyAnimator +
 * withLayer() only — the cheapest path an old GPU has.
 *
 * Note: withEndAction does NOT fire on cancel(), so a stopped slideshow
 * must clean views up itself (SlideshowController.stop does).
 */
object Transitions {

    private const val FADE_MS = 400L
    private const val SLIDE_MS = 450L
    private const val ZOOM_MS = 500L
    private const val HALF_FADE_MS = 250L

    fun run(
        effect: TransitionEffect,
        outgoing: ImageView,
        incoming: ImageView,
        onEnd: () -> Unit,
    ) {
        when (effect) {
            TransitionEffect.CROSSFADE,
            TransitionEffect.KEN_BURNS, // drift runs while displayed; the switch itself is a crossfade
            -> {
                prepareOnTop(incoming)
                incoming.alpha = 0f
                outgoing.animate().alpha(0f).setDuration(FADE_MS).withLayer().start()
                incoming.animate().alpha(1f).setDuration(FADE_MS).withLayer()
                    .withEndAction { finish(outgoing, incoming, onEnd) }.start()
            }

            TransitionEffect.FADE_BLACK -> {
                prepareOnTop(incoming)
                incoming.alpha = 0f
                outgoing.animate().alpha(0f).setDuration(HALF_FADE_MS)
                    .setInterpolator(AccelerateInterpolator()).withLayer()
                    .withEndAction {
                        incoming.animate().alpha(1f).setDuration(HALF_FADE_MS)
                            .setInterpolator(DecelerateInterpolator()).withLayer()
                            .withEndAction { finish(outgoing, incoming, onEnd) }.start()
                    }.start()
            }

            TransitionEffect.SLIDE_LEFT -> slide(outgoing, incoming, -1f, 0f, onEnd)
            TransitionEffect.SLIDE_RIGHT -> slide(outgoing, incoming, 1f, 0f, onEnd)
            TransitionEffect.SLIDE_UP -> slide(outgoing, incoming, 0f, -1f, onEnd)
            TransitionEffect.SLIDE_DOWN -> slide(outgoing, incoming, 0f, 1f, onEnd)

            TransitionEffect.ZOOM_IN -> {
                prepareOnTop(incoming)
                incoming.alpha = 0f
                incoming.scaleX = 0.7f
                incoming.scaleY = 0.7f
                outgoing.animate().alpha(0f).setDuration(ZOOM_MS).withLayer().start()
                incoming.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(ZOOM_MS).setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .withEndAction { finish(outgoing, incoming, onEnd) }.start()
            }

            TransitionEffect.ZOOM_OUT -> {
                // The only effect where the OLD photo stays on top and falls away.
                outgoing.bringToFront()
                incoming.visibility = View.VISIBLE
                incoming.alpha = 1f
                outgoing.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f)
                    .setDuration(ZOOM_MS).setInterpolator(AccelerateInterpolator())
                    .withLayer()
                    .withEndAction { finish(outgoing, incoming, onEnd) }.start()
            }

            TransitionEffect.ROTATE_FADE -> {
                prepareOnTop(incoming)
                incoming.alpha = 0f
                incoming.rotation = -8f
                outgoing.animate().alpha(0f).setDuration(SLIDE_MS).withLayer().start()
                incoming.animate().alpha(1f).rotation(0f)
                    .setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .withEndAction { finish(outgoing, incoming, onEnd) }.start()
            }

            TransitionEffect.RANDOM ->
                // Must be resolved by the caller (TransitionEffect.resolve).
                run(TransitionEffect.CROSSFADE, outgoing, incoming, onEnd)
        }
    }

    private fun slide(
        outgoing: ImageView,
        incoming: ImageView,
        directionX: Float,
        directionY: Float,
        onEnd: () -> Unit,
    ) {
        val width = incoming.width.takeIf { it > 0 }
            ?: incoming.resources.displayMetrics.widthPixels
        val height = incoming.height.takeIf { it > 0 }
            ?: incoming.resources.displayMetrics.heightPixels
        val fromX = -directionX * width
        val fromY = -directionY * height
        prepareOnTop(incoming)
        incoming.alpha = 1f
        incoming.translationX = fromX
        incoming.translationY = fromY
        outgoing.animate()
            .translationX(directionX * width).translationY(directionY * height)
            .setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator())
            .withLayer().start()
        incoming.animate().translationX(0f).translationY(0f)
            .setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator())
            .withLayer()
            .withEndAction { finish(outgoing, incoming, onEnd) }.start()
    }

    private fun prepareOnTop(incoming: ImageView) {
        incoming.bringToFront()
        incoming.visibility = View.VISIBLE
    }

    /** Neutralize every animated property so the next effect starts clean. */
    fun resetProperties(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotation = 0f
    }

    private fun finish(outgoing: View, incoming: View, onEnd: () -> Unit) {
        resetProperties(outgoing)
        resetProperties(incoming)
        outgoing.visibility = View.INVISIBLE
        incoming.visibility = View.VISIBLE
        onEnd()
    }
}
