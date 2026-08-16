package com.project01.session

import java.security.MessageDigest
import java.util.Locale
import java.security.SecureRandom

object PasswordHasher {

    private val secureRandom = SecureRandom()

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.toHex()
    }

    fun hash(password: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (password + nonce).toByteArray(Charsets.UTF_8)
        return digest.digest(input).toHex()
    }

    /**
     * Locale-independent hex, deliberately.
     *
     * `"%02x".format(b)` formats with the device's DEFAULT locale. These strings go on the
     * wire and are compared byte-for-byte between phones: the nonce the host generates and
     * the hash a player computes from it. If any device's locale ever rendered digits
     * differently, that player could never authenticate — on a fleet of unknown phones with
     * unknown locale settings, that is not a risk worth carrying for zero benefit.
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
