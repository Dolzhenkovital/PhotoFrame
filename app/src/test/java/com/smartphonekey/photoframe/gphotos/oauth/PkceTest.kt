package com.smartphonekey.photoframe.gphotos.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun `challenge matches RFC 7636 appendix B reference vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier uses only unreserved characters at a valid length`() {
        val verifier = Pkce.newVerifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
    }

    @Test
    fun `verifier and state are not repeated`() {
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
        assertNotEquals(Pkce.newState(), Pkce.newState())
    }

    @Test
    fun `challenge has no base64 padding`() {
        val challenge = Pkce.challengeS256(Pkce.newVerifier())
        assertEquals(43, challenge.length) // 32 bytes → 43 base64url chars
        assertTrue('=' !in challenge)
        assertTrue('+' !in challenge)
        assertTrue('/' !in challenge)
    }
}
