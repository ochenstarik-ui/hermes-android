package app.hermes.mobile.core.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class PkceChallenge(
    val codeVerifier: String,
    val codeChallenge: String,
    val method: String = "S256"
) {
    companion object {
        private val SECURE_RANDOM = SecureRandom()
        private const val PKCE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        fun generate(length: Int = 64): PkceChallenge {
            require(length in 43..128) { "PKCE code_verifier length must be between 43 and 128 characters" }
            val sb = StringBuilder(length)
            for (i in 0 until length) {
                val index = SECURE_RANDOM.nextInt(PKCE_CHARSET.length)
                sb.append(PKCE_CHARSET[index])
            }
            val verifier = sb.toString()
            val challenge = computeChallenge(verifier)
            return PkceChallenge(
                codeVerifier = verifier,
                codeChallenge = challenge,
                method = "S256"
            )
        }

        fun computeChallenge(verifier: String): String {
            val bytes = verifier.toByteArray(StandardCharsets.US_ASCII)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return base64UrlEncodeNoPadding(digest)
        }

        fun base64UrlEncodeNoPadding(input: ByteArray): String {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input)
        }
    }
}
