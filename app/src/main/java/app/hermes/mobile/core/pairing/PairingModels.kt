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
)

sealed interface PairingValidationResult {
    data class Success(val payload: HermesPairingPayload) : PairingValidationResult
    data class Expired(val expiresAt: Long) : PairingValidationResult
    data class InvalidScheme(val reason: String) : PairingValidationResult
    data class InvalidVersion(val version: Int) : PairingValidationResult
    data class InvalidPayload(val reason: String) : PairingValidationResult
}
