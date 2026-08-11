package com.insurance.renewal.backend

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Must match the Android app's [PasswordHasher] (salted SHA-256).
 * Admin sets plaintext passwords; the server stores only the hash.
 */
object PasswordHasher {
    private const val SALT = "renewal-agent-v1"

    fun hash(password: String, salt: String = SALT): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(password: String, hash: String): Boolean = hash(password) == hash

    /** Short temporary password for admin to share offline (phone/WhatsApp). */
    fun generateTempPassword(length: Int = 8): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rng = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
    }
}
