package app.sine.core.subsonic

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Subsonic token authentication (API 1.13+): token = md5(password + salt).
 * Sine stores the token and salt, never the password.
 */
data class SubsonicCredentials(val username: String, val token: String, val salt: String) {
    companion object {
        fun fromPassword(username: String, password: String, salt: String = newSalt()) =
            SubsonicCredentials(username, md5Hex(password + salt), salt)

        fun newSalt(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
