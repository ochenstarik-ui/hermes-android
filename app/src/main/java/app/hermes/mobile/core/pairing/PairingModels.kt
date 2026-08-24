package app.hermes.mobile.core.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HermesPairingPayload(
    val v: Int,
    val type: String,
    @SerialName("host_id") val hostId: String,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String = "http",
    @SerialName("expires_at") val expiresAt: Long,
    val nonce: String
) {
    val canonicalEndpoint: CanonicalEndpoint
        get() = CanonicalEndpoint(
            scheme = scheme.lowercase(),
            host = host.lowercase(),
            port = if (port == 0) {
                if (scheme.lowercase() == "https") 443 else 80
            } else port
        )
}

data class CanonicalEndpoint(val scheme: String, val host: String, val port: Int) {
    companion object {
        fun fromBaseUrl(baseUrl: String): CanonicalEndpoint {
            return try {
                val uri = java.net.URI(baseUrl)
                val scheme = uri.scheme?.lowercase() ?: "http"
                val host = uri.host?.lowercase() ?: ""
                val port = if (uri.port == -1 || uri.port == 0) {
                    if (scheme == "https") 443 else 80
                } else uri.port
                CanonicalEndpoint(scheme, host, port)
            } catch (e: Exception) {
                CanonicalEndpoint("http", "", 80)
            }
        }
    }
}

sealed interface PairingValidationResult {
    data class Success(val payload: HermesPairingPayload) : PairingValidationResult
    data class Expired(val expiresAt: Long) : PairingValidationResult
    data class InvalidScheme(val reason: String) : PairingValidationResult
    data class InvalidVersion(val version: Int) : PairingValidationResult
    data class InvalidPayload(val reason: String) : PairingValidationResult
}
