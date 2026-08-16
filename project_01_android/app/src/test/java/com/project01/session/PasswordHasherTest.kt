package com.project01.session

import org.junit.Test
import org.junit.Assert.*

class PasswordHasherTest {

    @Test
    fun `generateNonce returns 64 character hex string`() {
        val nonce = PasswordHasher.generateNonce()
        assertEquals(64, nonce.length)
        assertTrue(nonce.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `generateNonce returns unique values`() {
        val nonces = (1..100).map { PasswordHasher.generateNonce() }.toSet()
        assertEquals(100, nonces.size)
    }

    @Test
    fun `hash returns consistent result for same input`() {
        val hash1 = PasswordHasher.hash("password123", "abc")
        val hash2 = PasswordHasher.hash("password123", "abc")
        assertEquals(hash1, hash2)
    }

    @Test
    fun `hash returns 64 character hex string`() {
        val hash = PasswordHasher.hash("test", "nonce")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hash differs for different passwords`() {
        val nonce = PasswordHasher.generateNonce()
        val hash1 = PasswordHasher.hash("password1", nonce)
        val hash2 = PasswordHasher.hash("password2", nonce)
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hash differs for different nonces`() {
        val hash1 = PasswordHasher.hash("password", "nonce1")
        val hash2 = PasswordHasher.hash("password", "nonce2")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hash works with empty password`() {
        val hash = PasswordHasher.hash("", "nonce")
        assertEquals(64, hash.length)
    }

    @Test
    fun `hash works with empty nonce`() {
        val hash = PasswordHasher.hash("password", "")
        assertEquals(64, hash.length)
    }

    @Test
    fun `hash and nonce are plain ASCII hex whatever the device locale is`() {
        // These strings are compared byte-for-byte across phones — the host generates the
        // nonce, a player hashes against it. The fleet is unknown devices with unknown locale
        // settings, so formatting must not depend on the default locale.
        val original = java.util.Locale.getDefault()
        try {
            for (locale in listOf(java.util.Locale("ar", "EG"), java.util.Locale.GERMANY,
                                  java.util.Locale("hi", "IN"), java.util.Locale.ROOT)) {
                java.util.Locale.setDefault(locale)

                val hash = PasswordHasher.hash("secret", "n0nce")
                val nonce = PasswordHasher.generateNonce()

                assertTrue("hash must be ASCII hex under $locale, was $hash",
                    hash.matches(Regex("[0-9a-f]{64}")))
                assertTrue("nonce must be ASCII hex under $locale, was $nonce",
                    nonce.matches(Regex("[0-9a-f]{64}")))
            }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun `the same password and nonce hash identically across locales`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("ar", "EG"))
            val fromArabicLocale = PasswordHasher.hash("secret", "n0nce")
            java.util.Locale.setDefault(java.util.Locale.US)
            val fromUsLocale = PasswordHasher.hash("secret", "n0nce")

            assertEquals("a player must be able to authenticate against any host",
                fromUsLocale, fromArabicLocale)
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
