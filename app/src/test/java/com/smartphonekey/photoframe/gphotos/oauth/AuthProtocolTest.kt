package com.smartphonekey.photoframe.gphotos.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProtocolTest {

    @Test
    fun `authorization url carries every oauth parameter`() {
        val url = AuthProtocol.authorizationUrl(
            clientId = "id-123.apps.googleusercontent.com",
            port = 43210,
            codeChallenge = "challenge",
            state = "state-xyz",
            forceConsent = true,
        )
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue("client_id=id-123.apps.googleusercontent.com" in url)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A43210" in url)
        assertTrue("response_type=code" in url)
        assertTrue("code_challenge=challenge" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=state-xyz" in url)
        assertTrue("access_type=offline" in url)
        assertTrue("prompt=consent" in url)
        assertTrue("photospicker.mediaitems.readonly" in url)
    }

    @Test
    fun `consent prompt only forced when asked`() {
        val url = AuthProtocol.authorizationUrl("id", 1, "c", "s", forceConsent = false)
        assertTrue("prompt=consent" !in url)
    }

    @Test
    fun `redirect with code and matching state is accepted`() {
        val r = AuthProtocol.parseRequestLine(
            "GET /?state=abc&code=4%2FxyzToken HTTP/1.1", "abc"
        )
        assertEquals(AuthProtocol.Redirect.Code("4/xyzToken"), r)
    }

    @Test
    fun `state mismatch is ignored not errored`() {
        val r = AuthProtocol.parseRequestLine(
            "GET /?state=evil&code=stolen HTTP/1.1", "abc"
        )
        assertEquals(AuthProtocol.Redirect.Ignore, r)
    }

    @Test
    fun `denied consent maps to error`() {
        val r = AuthProtocol.parseRequestLine(
            "GET /?error=access_denied&state=abc HTTP/1.1", "abc"
        )
        assertEquals(AuthProtocol.Redirect.Error("access_denied"), r)
    }

    @Test
    fun `favicon probe and garbage are ignored`() {
        assertEquals(
            AuthProtocol.Redirect.Ignore,
            AuthProtocol.parseRequestLine("GET /favicon.ico HTTP/1.1", "abc"),
        )
        assertEquals(
            AuthProtocol.Redirect.Ignore,
            AuthProtocol.parseRequestLine("POST / HTTP/1.1", "abc"),
        )
        assertEquals(
            AuthProtocol.Redirect.Ignore,
            AuthProtocol.parseRequestLine("", "abc"),
        )
        assertEquals(
            AuthProtocol.Redirect.Ignore,
            AuthProtocol.parseRequestLine("GET /?code=%zz&state=abc HTTP/1.1", "abc"),
        )
    }

    @Test
    fun `empty code is ignored`() {
        assertEquals(
            AuthProtocol.Redirect.Ignore,
            AuthProtocol.parseRequestLine("GET /?state=abc&code= HTTP/1.1", "abc"),
        )
    }

    @Test
    fun `token bodies escape their values`() {
        val body = AuthProtocol.codeExchangeBody(
            clientId = "id",
            clientSecret = "s&cret",
            code = "4/code",
            verifier = "ver~ifier",
            port = 8080,
        )
        assertTrue("grant_type=authorization_code" in body)
        assertTrue("code=4%2Fcode" in body)
        assertTrue("client_secret=s%26cret" in body)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080" in body)

        val refresh = AuthProtocol.refreshBody("id", "sec", "1//refresh")
        assertTrue("grant_type=refresh_token" in refresh)
        assertTrue("refresh_token=1%2F%2Frefresh" in refresh)
    }
}
