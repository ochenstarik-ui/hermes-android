package app.hermes.mobile.core.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingPayloadV1(
    val v: Int,
    val type: String,
    @SerialName("host_id") val hostId: String,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String = "http",
    @SerialName("expires_at") val expiresAt: Long,
    val nonce: String,
    @SerialName("fingerprint") val fingerprint: String? = null
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

typealias HermesPairingPayload = PairingPayloadV1

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

sealed class PairingResult {
    data class Success(val payload: PairingPayloadV1) : PairingResult()
    data class Failure(val error: PairingError) : PairingResult()
}

sealed class PairingError(val code: String, val message: String) {
    data class InvalidUriFormat(val reason: String = "URI does not match hermes://pair or hermes:/pair") :
        PairingError("invalid_uri_scheme", reason)

    data class MissingDataParam(val reason: String = "Missing 'data' query parameter") :
        PairingError("missing_data_param", reason)

    data class EmptyData(val reason: String = "Empty 'data' query parameter") :
        PairingError("empty_data", reason)

    data class Base64DecodeError(val reason: String = "Failed to decode Base64 payload") :
        PairingError("corrupted_base64", reason)

    data class JsonSyntaxError(val reason: String = "Invalid JSON payload") :
        PairingError("invalid_json", reason)

    data class InvalidPayloadType(val type: String) :
        PairingError("wrong_type", "Invalid payload type: $type")

    data class UnsupportedProtocolVersion(val version: Int) :
        PairingError("wrong_version", "Unsupported payload version: $version")

    data class InvalidHostId(val hostId: String) :
        PairingError("invalid_uuid", "Invalid host UUID: $hostId")

    data class InvalidName(val reason: String) :
        PairingError("invalid_name", reason)

    data class EmptyHost(val reason: String = "Host address cannot be empty") :
        PairingError("empty_host", reason)

    data class InvalidHost(val reason: String) :
        PairingError("invalid_host", reason)

    data class InvalidPort(val port: Int) :
        PairingError("invalid_port_zero", "Invalid port: $port")

    data class InvalidScheme(val scheme: String) :
        PairingError("invalid_scheme", "Invalid scheme: $scheme")

    data class InvalidNonce(val reason: String) :
        PairingError("invalid_nonce_length", reason)

    data class InvalidFingerprint(val reason: String = "Invalid certificate fingerprint") :
        PairingError("invalid_fingerprint", reason)

    data class ExpiredPayload(val expiresAt: Long) :
        PairingError("expired_payload", "Payload expired at $expiresAt")

    data class ClockSkewError(val expiresAt: Long) :
        PairingError("clock_skew_error", "Expiry exceeds maximum TTL: $expiresAt")

    data class NonceReused(val nonce: String) :
        PairingError("nonce_reused", "Nonce has already been used on this device")

    data class PayloadTooLarge(val reason: String) :
        PairingError("payload_too_large", reason)

    data class GenericError(val reason: String) :
        PairingError("generic_error", reason)
}
