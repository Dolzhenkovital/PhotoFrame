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
     * [retainedBytes] is what the cache will actually hold after deleting
     * [victimIds] — the "cache too small" signal must be judged from it, not
     * from any hypothetical smallest subset: LRU chooses what stays by
     * recency, not by size, so the retained set can exceed the cap even when
     * some other combination of files would have fit.
     */
    data class Plan(val victimIds: List<String>, val retainedBytes: Long)

    /**
     * Plans an eviction: least-recently-USED first, until the total fits
     * [capBytes] — but never shrinking the set below [minKeep] items when
     * more than [minKeep] exist (better an over-budget cache than a frame
     * showing three photos in a loop; the caller surfaces "cache too small"
     * when the plan's retainedBytes still exceed the cap).
     *
     * "Used" is max(lastShownAt, downloadedAt): a freshly downloaded photo
     * that has not been shown yet must not be the first eviction victim.
     */
    fun plan(
        entries: List<Entry>,
        capBytes: Long,
        minKeep: Int = MIN_KEEP,
    ): Plan {
        var total = entries.sumOf { it.sizeBytes }
        val victims = ArrayList<String>()
        var remaining = entries.size
        for (entry in entries.sortedBy { maxOf(it.lastShownAt, it.downloadedAt) }) {
            if (total <= capBytes || remaining <= minKeep) break
            victims.add(entry.id)
            total -= entry.sizeBytes
            remaining--
        }
        return Plan(victims, total)
    }
}
