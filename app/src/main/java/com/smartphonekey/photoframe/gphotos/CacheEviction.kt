package com.smartphonekey.photoframe.gphotos

/**
 * LRU eviction policy for the Google Photos cache (caching.md reference).
 * Pure function so the rules are unit-tested on the JVM.
 */
object CacheEviction {

    /** A frame looping over fewer photos than this looks broken. */
    const val MIN_KEEP = 20

    data class Entry(
        val id: String,
        val sizeBytes: Long,
        val lastShownAt: Long,
        val downloadedAt: Long,
    )

    /**
     * Returns ids to delete, least-recently-USED first, until the total fits
     * [capBytes] — but never shrinking the set below [minKeep] items when
     * more than [minKeep] exist (better an over-budget cache than a frame
     * showing three photos in a loop; the caller surfaces "cache too small").
     *
     * "Used" is max(lastShownAt, downloadedAt): a freshly downloaded photo
     * that has not been shown yet must not be the first eviction victim.
     */
    fun selectVictims(
        entries: List<Entry>,
        capBytes: Long,
        minKeep: Int = MIN_KEEP,
    ): List<String> {
        var total = entries.sumOf { it.sizeBytes }
        if (total <= capBytes) return emptyList()
        val victims = ArrayList<String>()
        var remaining = entries.size
        for (entry in entries.sortedBy { maxOf(it.lastShownAt, it.downloadedAt) }) {
            if (total <= capBytes || remaining <= minKeep) break
            victims.add(entry.id)
            total -= entry.sizeBytes
            remaining--
        }
        return victims
    }

    /** True when the cap cannot hold [entries] even after full eviction. */
    fun capTooSmall(entries: List<Entry>, capBytes: Long, minKeep: Int = MIN_KEEP): Boolean {
        if (entries.size <= minKeep) {
            return entries.sumOf { it.sizeBytes } > capBytes
        }
        val smallestKeep = entries.map { it.sizeBytes }.sorted().take(minKeep).sum()
        return smallestKeep > capBytes
    }
}
