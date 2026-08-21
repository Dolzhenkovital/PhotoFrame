package com.smartphonekey.photoframe.gphotos.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-out race rule (PR #11 review): a token operation snapshots the
 * generation at start; signOut() invalidates; the operation's result must
 * be dropped, never written back into the cleared store.
 */
class AuthGenerationTest {

    @Test
    fun `operation started before sign-out is invalidated`() {
        val g = AuthGeneration()
        val inFlight = g.current() // exchange/refresh starts
        g.invalidate() // user signs out while HTTP is in flight
        assertFalse(g.isCurrent(inFlight)) // response arrives → dropped
    }

    @Test
    fun `operation started after sign-out is current`() {
        val g = AuthGeneration()
        g.invalidate()
        val fresh = g.current() // a new sign-in attempt afterwards
        assertTrue(g.isCurrent(fresh))
    }

    @Test
    fun `undisturbed operation stays current`() {
        val g = AuthGeneration()
        val snapshot = g.current()
        assertTrue(g.isCurrent(snapshot))
    }

    @Test
    fun `every sign-out kills every earlier snapshot`() {
        val g = AuthGeneration()
        val first = g.current()
        g.invalidate()
        val second = g.current()
        g.invalidate()
        assertFalse(g.isCurrent(first))
        assertFalse(g.isCurrent(second))
    }
}
