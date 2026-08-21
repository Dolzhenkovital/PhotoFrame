package com.smartphonekey.photoframe.core

import kotlin.random.Random

/**
 * Orientation-aware playback order.
 *
 * Each cycle shows photos matching the device orientation first (plus
 * SQUARE, which fits both), then the rest — wrong-orientation photos are
 * letterboxed, never dropped. Within each segment the order is shuffled,
 * and a no-repeat window keeps fresh cycles from starting with the photos
 * that were just on screen.
 *
 * Pure Kotlin with an injectable [Random] — fully unit-tested on the JVM.
 */
class PlaybackQueue(private val random: Random = Random.Default) {

    private var all: List<PhotoItem> = emptyList()
    private var deviceOrientation: PhotoOrientation = PhotoOrientation.LANDSCAPE
    private val queue = ArrayDeque<PhotoItem>()
    private val recent = ArrayDeque<String>()

    val size: Int get() = all.size

    fun setPhotos(photos: List<PhotoItem>) {
        all = photos.distinctBy { it.uri }
        queue.clear()
        val alive = all.mapTo(HashSet()) { it.uri }
        recent.retainAll { it in alive }
    }

    fun setDeviceOrientation(orientation: PhotoOrientation) {
        if (orientation != deviceOrientation) {
            deviceOrientation = orientation
            queue.clear() // rebuild with the new preference on next tick
        }
    }

    fun next(): PhotoItem? {
        if (all.isEmpty()) return null
        if (queue.isEmpty()) refill()
        val item = queue.removeFirst()
        remember(item.uri)
        return item
    }

    private fun matches(item: PhotoItem): Boolean =
        item.orientation == deviceOrientation || item.orientation == PhotoOrientation.SQUARE

    private fun refill() {
        val (preferred, fallback) = all.partition(::matches)
        val recentSet = recent.toHashSet()
        // Recently-shown photos sink to the back of their own segment so the
        // orientation preference is preserved while repeats are delayed.
        fun ordered(segment: List<PhotoItem>): List<PhotoItem> {
            val shuffled = segment.shuffled(random)
            val (seen, fresh) = shuffled.partition { it.uri in recentSet }
            return fresh + seen
        }
        queue.clear()
        queue.addAll(ordered(preferred))
        queue.addAll(ordered(fallback))
        // Hard guarantee: never the same photo twice in a row (unless it is
        // the only photo there is).
        val last = recent.lastOrNull()
        if (queue.size > 1 && queue.first().uri == last) {
            val head = queue.removeFirst()
            val second = queue.removeFirst()
            queue.addFirst(head)
            queue.addFirst(second)
        }
    }

    private fun remember(uri: String) {
        recent.remove(uri)
        recent.addLast(uri)
        val window = minOf(all.size / 2, 20)
        while (recent.size > window) recent.removeFirst()
    }
}
