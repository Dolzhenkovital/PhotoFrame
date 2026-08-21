package com.smartphonekey.photoframe.gphotos.oauth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (RFC 7636) values for the loopback OAuth flow. Pure Kotlin —
 * JVM-tested against the RFC's reference vector.
 */
object Pkce {

    private const val VERIFIER_LENGTH = 64
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** 64 chars from the RFC's unreserved set (43–128 allowed). */
    fun newVerifier(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) {
            sb.append(UNRESERVED[random.nextInt(UNRESERVED.length)])
        }
        return sb.toString()
    }

    /** S256: BASE64URL-ENCODE(SHA256(ASCII(verifier))), no padding. */
    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    /** Random `state` value to bind the redirect to this request. */
    fun newState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    // By hand instead of android.util.Base64 so the JVM tests can run it;
    // java.util.Base64 needs API 26 and this app runs on 23.
    private fun base64Url(bytes: ByteArray): String {
        val table =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(table[b0 shr 2])
            if (b1 >= 0) {
                sb.append(table[(b0 shl 4 or (b1 shr 4)) and 0x3F])
                if (b2 >= 0) {
                    sb.append(table[(b1 shl 2 or (b2 shr 6)) and 0x3F])
                    sb.append(table[b2 and 0x3F])
                } else {
                    sb.append(table[(b1 shl 2) and 0x3F])
                }
            } else {
                sb.append(table[(b0 shl 4) and 0x3F])
            }
            i += 3
        }
        return sb.toString() // no padding on purpose (base64url)
    }
}
