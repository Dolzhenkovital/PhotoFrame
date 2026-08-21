package com.smartphonekey.photoframe.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private fun portrait(id: String) = PhotoItem("uri://$id", id, 0, 0, 100, 200)
    private fun landscape(id: String) = PhotoItem("uri://$id", id, 0, 0, 200, 100)
    private fun square(id: String) = PhotoItem("uri://$id", id, 0, 0, 100, 100)

    private fun queue(seed: Int = 42) = PlaybackQueue(Random(seed))

    @Test
    fun `empty source yields null`() {
        assertNull(queue().next())
    }

    @Test
    fun `single photo repeats forever`() {
        val q = queue()
        q.setPhotos(listOf(portrait("only")))
        repeat(5) { assertEquals("only", q.next()?.displayName) }
    }

    @Test
    fun `matching orientation and square play before fallback`() {
        val q = queue()
        q.setDeviceOrientation(PhotoOrientation.PORTRAIT)
        q.setPhotos(
            listOf(
                portrait("p1"), portrait("p2"), portrait("p3"),
                square("s1"),
                landscape("l1"), landscape("l2"), landscape("l3"),
            )
        )
        val firstFour = (1..4).map { q.next()!!.orientation }
        firstFour.forEach { assertNotEquals(PhotoOrientation.LANDSCAPE, it) }
        val lastThree = (1..3).map { q.next()!!.orientation }
        lastThree.forEach { assertEquals(PhotoOrientation.LANDSCAPE, it) }
    }

    @Test
    fun `a full cycle shows every photo exactly once`() {
        val q = queue()
        q.setDeviceOrientation(PhotoOrientation.LANDSCAPE)
        val photos = listOf(
            portrait("p1"), portrait("p2"),
            landscape("l1"), landscape("l2"),
            square("s1"),
        )
        q.setPhotos(photos)
        val shown = (1..photos.size).map { q.next()!!.uri }
        assertEquals(photos.map { it.uri }.toSet(), shown.toSet())
        assertEquals(photos.size, shown.distinct().size)
    }

    @Test
    fun `no photo shows twice in a row across cycles`() {
        val q = queue()
        q.setPhotos(listOf(portrait("a"), portrait("b")))
        var previous: String? = null
        repeat(20) {
            val current = q.next()!!.uri
            assertNotEquals("repeat at step $it", previous, current)
            previous = current
        }
    }

    @Test
    fun `orientation switch rebuilds the preference`() {
        val q = queue()
        q.setDeviceOrientation(PhotoOrientation.PORTRAIT)
        q.setPhotos(
            listOf(portrait("p1"), portrait("p2"), landscape("l1"), landscape("l2"))
        )
        assertEquals(PhotoOrientation.PORTRAIT, q.next()!!.orientation)
        q.setDeviceOrientation(PhotoOrientation.LANDSCAPE)
        assertEquals(PhotoOrientation.LANDSCAPE, q.next()!!.orientation)
    }

    @Test
    fun `replacing photos with a smaller set keeps working`() {
        val q = queue()
        q.setPhotos(listOf(portrait("a"), portrait("b"), portrait("c")))
        repeat(3) { assertNotNull(q.next()) }
        q.setPhotos(listOf(portrait("z")))
        repeat(3) { assertEquals("z", q.next()?.displayName) }
    }

    @Test
    fun `duplicate uris are collapsed`() {
        val q = queue()
        q.setPhotos(listOf(portrait("a"), portrait("a"), portrait("b")))
        assertEquals(2, q.size)
        val cycle = setOf(q.next()!!.uri, q.next()!!.uri)
        assertEquals(2, cycle.size)
    }

    @Test
    fun `wrong-orientation photos are letterboxed not dropped`() {
        val q = queue()
        q.setDeviceOrientation(PhotoOrientation.PORTRAIT)
        // 95% landscape library on a vertical frame — everything still shows.
        val photos = (1..19).map { landscape("l$it") } + portrait("p1")
        q.setPhotos(photos)
        val shown = (1..photos.size).map { q.next()!!.uri }.toSet()
        assertTrue(shown.containsAll(photos.map { it.uri }))
    }
}
