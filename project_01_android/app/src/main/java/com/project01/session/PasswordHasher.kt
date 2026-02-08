package com.project01.session

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {

    private val secureRandom = SecureRandom()

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hash(password: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (password + nonce).toByteArray(Charsets.UTF_8)
        return digest.digest(input).joinToString("") { "%02x".format(it) }
    }
}
