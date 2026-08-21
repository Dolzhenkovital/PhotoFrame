package com.smartphonekey.photoframe.gphotos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules protect two credentials-adjacent paths: the QR code the user
 * scans with their phone, and the bearer token attached to media downloads.
 * Each case below is an attack that must stay blocked.
 */
class PickerUrisTest {

    @Test
    fun `genuine picker url is accepted`() {
        assertTrue(PickerUris.isTrustedPickerUri("https://photos.google.com/picker/abc123"))
        assertTrue(PickerUris.isTrustedPickerUri("HTTPS://Photos.Google.COM/x"))
    }

    @Test
    fun `picker host must match exactly`() {
        // A wildcard over google.com would accept any Google-hosted user
        // content — too broad for a link we ask the user to scan.
        assertFalse(PickerUris.isTrustedPickerUri("https://evil.com/picker"))
        assertFalse(PickerUris.isTrustedPickerUri("https://photos.google.com.evil.com/x"))
        assertFalse(PickerUris.isTrustedPickerUri("https://sites.google.com/x"))
        assertFalse(PickerUris.isTrustedPickerUri("https://photos.google.evil.com/x"))
    }

    @Test
    fun `non-https schemes are rejected`() {
        assertFalse(PickerUris.isTrustedPickerUri("http://photos.google.com/x"))
        assertFalse(PickerUris.isTrustedPickerUri("intent://photos.google.com/x#Intent;end"))
        assertFalse(PickerUris.isTrustedPickerUri("javascript:alert(1)"))
        assertFalse(PickerUris.isTrustedPickerUri("file:///data/data/app/secret"))
    }

    @Test
    fun `credentials in the authority are rejected`() {
        // Classic look-alike: the real host here is evil.com.
        assertFalse(PickerUris.isTrustedPickerUri("https://photos.google.com@evil.com/x"))
    }

    @Test
    fun `garbage input is rejected without throwing`() {
        assertFalse(PickerUris.isTrustedPickerUri(""))
        assertFalse(PickerUris.isTrustedPickerUri("   "))
        assertFalse(PickerUris.isTrustedPickerUri("not a url at all"))
        assertFalse(PickerUris.isTrustedMediaBaseUrl("https://"))
    }

    @Test
    fun `google media hosts are accepted for downloads`() {
        assertTrue(PickerUris.isTrustedMediaBaseUrl("https://lh3.googleusercontent.com/abc"))
        assertTrue(PickerUris.isTrustedMediaBaseUrl("https://lh6.googleusercontent.com/x"))
    }

    @Test
    fun `bearer token never goes to a foreign host`() {
        assertFalse(PickerUris.isTrustedMediaBaseUrl("https://attacker.example/steal"))
        assertFalse(PickerUris.isTrustedMediaBaseUrl("https://googleusercontent.com.evil.io/x"))
        assertFalse(PickerUris.isTrustedMediaBaseUrl("http://lh3.googleusercontent.com/x"))
        assertFalse(PickerUris.isTrustedMediaBaseUrl("https://lh3.googleusercontent.com@evil/x"))
    }
}
