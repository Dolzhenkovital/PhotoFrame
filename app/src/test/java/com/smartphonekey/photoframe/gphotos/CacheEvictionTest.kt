package com.smartphonekey.photoframe.gphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheEvictionTest {

    private fun entry(id: String, size: Long, shown: Long = 0, downloaded: Long = 0) =
        CacheEviction.Entry(id, size, shown, downloaded)

    @Test
    fun `under cap evicts nothing`() {
        val victims = CacheEviction.selectVictims(
            listOf(entry("a", 100), entry("b", 100)),
            capBytes = 1000,
        )
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `least recently used goes first`() {
        val victims = CacheEviction.selectVictims(
            listOf(
                entry("old", 400, shown = 10),
                entry("mid", 400, shown = 50),
                entry("new", 400, shown = 90),
            ),
            capBytes = 800,
            minKeep = 1,
        )
        assertEquals(listOf("old"), victims)
    }

    @Test
    fun `fresh downloads are not the first victims`() {
        // Never shown, but just downloaded — its effective use time is recent.
        val victims = CacheEviction.selectVictims(
            listOf(
                entry("fresh", 400, shown = 0, downloaded = 100),
                entry("stale", 400, shown = 10, downloaded = 5),
            ),
            capBytes = 400,
            minKeep = 1,
        )
        assertEquals(listOf("stale"), victims)
    }

    @Test
    fun `never shrinks below minKeep`() {
        val entries = (1..25).map { entry("e$it", 100, shown = it.toLong()) }
        val victims = CacheEviction.selectVictims(entries, capBytes = 100, minKeep = 20)
        assertEquals(5, victims.size) // 25 → 20, even though still over cap
    }

    @Test
    fun `small sets are never evicted at all`() {
        val entries = (1..5).map { entry("e$it", 1000, shown = it.toLong()) }
        val victims = CacheEviction.selectVictims(entries, capBytes = 100, minKeep = 20)
        assertTrue(victims.isEmpty())
    }

    @Test
    fun `capTooSmall detects an impossible cap`() {
        val big = (1..25).map { entry("e$it", 1000) }
        assertTrue(CacheEviction.capTooSmall(big, capBytes = 10_000, minKeep = 20))
        assertFalse(CacheEviction.capTooSmall(big, capBytes = 30_000, minKeep = 20))
        val few = listOf(entry("a", 500), entry("b", 500))
        assertTrue(CacheEviction.capTooSmall(few, capBytes = 800, minKeep = 20))
        assertFalse(CacheEviction.capTooSmall(few, capBytes = 1_500, minKeep = 20))
    }
}
