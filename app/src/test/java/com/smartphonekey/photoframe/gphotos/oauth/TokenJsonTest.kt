package com.smartphonekey.photoframe.gphotos.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TokenJsonTest {

    @Test
    fun `full token response parses`() {
        val t = TokenJson.parse(
            """{"access_token":"ya29.a0","expires_in":3599,
                "refresh_token":"1//r","scope":"s","token_type":"Bearer"}"""
        )
        assertEquals("ya29.a0", t.accessToken)
        assertEquals(3599L, t.expiresInSeconds)
        assertEquals("1//r", t.refreshToken)
    }

    @Test
    fun `refresh response without rotation keeps refreshToken null`() {
        val t = TokenJson.parse(
            """{"access_token":"ya29.b1","expires_in":3599,"token_type":"Bearer"}"""
        )
        assertNull(t.refreshToken)
    }

    @Test
    fun `missing expires_in expires immediately, not never`() {
        val t = TokenJson.parse("""{"access_token":"x"}""")
        assertEquals(0L, t.expiresInSeconds)
    }

    @Test
    fun `oauth error body raises TokenError`() {
        try {
            TokenJson.parse(
                """{"error":"invalid_grant","error_description":"Token expired"}"""
            )
            fail("expected TokenError")
        } catch (e: TokenJson.TokenError) {
            assertEquals("invalid_grant", e.error)
            assertTrue(TokenJson.isInvalidGrant(e))
        }
    }

    @Test
    fun `response without access_token raises TokenError`() {
        try {
            TokenJson.parse("""{"token_type":"Bearer"}""")
            fail("expected TokenError")
        } catch (e: TokenJson.TokenError) {
            assertEquals("invalid_response", e.error)
        }
    }

    @Test
    fun `other errors are not invalid_grant`() {
        val e = TokenJson.TokenError("invalid_client", null)
        assertTrue(!TokenJson.isInvalidGrant(e))
        assertTrue(!TokenJson.isInvalidGrant(IllegalStateException("x")))
    }
}
