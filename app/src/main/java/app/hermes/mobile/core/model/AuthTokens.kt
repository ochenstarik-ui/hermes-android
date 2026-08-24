package app.hermes.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NativeAuthTokens(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String = "",
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_at")
    val expiresAt: Long = 0L,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    val provider: String = "",
    @SerialName("user_id")
    val userId: String = ""
) {
    fun normalizedExpiresAt(): Long {
        if (expiresAt > 0L) return expiresAt
        if (expiresIn != null && expiresIn > 0L) {
            return (System.currentTimeMillis() / 1000) + expiresIn
        }
        return 0L
    }
}
