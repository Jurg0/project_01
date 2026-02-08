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
}
