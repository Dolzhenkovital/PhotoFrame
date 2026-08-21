package com.smartphonekey.photoframe.slideshow

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TransitionEffectTest {

    @Test
    fun `unknown or missing pref falls back to crossfade`() {
        assertEquals(TransitionEffect.CROSSFADE, TransitionEffect.fromPref(null))
        assertEquals(TransitionEffect.CROSSFADE, TransitionEffect.fromPref("garbage"))
    }

    @Test
    fun `stored name round-trips`() {
        TransitionEffect.entries.forEach { effect ->
            assertEquals(effect, TransitionEffect.fromPref(effect.name))
        }
    }

    @Test
    fun `concrete selection resolves to itself`() {
        val random = Random(1)
        assertEquals(
            TransitionEffect.SLIDE_UP,
            TransitionEffect.resolve(TransitionEffect.SLIDE_UP, TransitionEffect.SLIDE_UP, random)
        )
    }

    @Test
    fun `random never yields RANDOM and never repeats the previous effect`() {
        val random = Random(7)
        var last: TransitionEffect? = TransitionEffect.CROSSFADE
        repeat(200) {
            val resolved = TransitionEffect.resolve(TransitionEffect.RANDOM, last, random)
            assertNotEquals(TransitionEffect.RANDOM, resolved)
            assertNotEquals(last, resolved)
            last = resolved
        }
    }
}
