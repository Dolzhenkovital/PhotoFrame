package com.smartphonekey.photoframe.gphotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheEvictionTest {

    private fun entry(id: String, size: Long, shown: Long = 0, downloaded: Long = 0) =
        CacheEviction.Entry(id, size, shown, downloaded)

    @Test
    fun `under cap evicts nothing and reports the real total`() {
        val plan = CacheEviction.plan(
            listOf(entry("a", 100), entry("b", 100)),
            capBytes = 1000,
        )
        assertTrue(plan.victimIds.isEmpty())
        assertEquals(200L, plan.retainedBytes)
    }

    @Test
    fun `least recently used goes first`() {
        val plan = CacheEviction.plan(
            listOf(
                entry("old", 400, shown = 10),
                entry("mid", 400, shown = 50),
                entry("new", 400, shown = 90),
            ),
            capBytes = 800,
            minKeep = 1,
        )
        assertEquals(listOf("old"), plan.victimIds)
        assertEquals(800L, plan.retainedBytes)
    }

    @Test
    fun `fresh downloads are not the first victims`() {
        // Never shown, but just downloaded — its effective use time is recent.
        val plan = CacheEviction.plan(
            listOf(
                entry("fresh", 400, shown = 0, downloaded = 100),
                entry("stale", 400, shown = 10, downloaded = 5),
            ),
            capBytes = 400,
            minKeep = 1,
        )
        assertEquals(listOf("stale"), plan.victimIds)
    }

    @Test
    fun `never shrinks below minKeep and reports the overshoot`() {
        val entries = (1..25).map { entry("e$it", 100, shown = it.toLong()) }
        val plan = CacheEviction.plan(entries, capBytes = 100, minKeep = 20)
        assertEquals(5, plan.victimIds.size) // 25 → 20, even though still over cap
        assertEquals(2000L, plan.retainedBytes)
        assertTrue(plan.retainedBytes > 100)
    }

    @Test
    fun `small sets are never evicted at all`() {
        val entries = (1..5).map { entry("e$it", 1000, shown = it.toLong()) }
        val plan = CacheEviction.plan(entries, capBytes = 100, minKeep = 20)
        assertTrue(plan.victimIds.isEmpty())
        assertEquals(5000L, plan.retainedBytes)
    }

    @Test
    fun `cap-too-small must be judged from the LRU-retained set`() {
        // Regression for the review finding: 20 tiny OLD photos + 20 large
        // NEW ones, cap 1000. LRU evicts the tiny old ones and keeps the
        // large recent ones (20 000 B) — way over cap. A hypothetical
        // "20 smallest files" set (200 B) would have fit, so judging by it
        // reported everything as fine while the disk stayed 20× over budget.
        val old = (1..20).map { entry("old$it", 10, shown = it.toLong()) }
        val fresh = (1..20).map { entry("new$it", 1000, shown = 100L + it) }
        val plan = CacheEviction.plan(old + fresh, capBytes = 1000, minKeep = 20)

        assertEquals(old.map { it.id }.toSet(), plan.victimIds.toSet())
        assertEquals(20_000L, plan.retainedBytes)
        assertTrue("must surface cache-too-small", plan.retainedBytes > 1000)
    }

    @Test
    fun `retained set fitting the cap reports no overshoot`() {
        val entries = (1..25).map { entry("e$it", 100, shown = it.toLong()) }
        val plan = CacheEviction.plan(entries, capBytes = 2100, minKeep = 20)
        assertEquals(4, plan.victimIds.size) // 2500 → 2100
        assertFalse(plan.retainedBytes > 2100)
    }
}
