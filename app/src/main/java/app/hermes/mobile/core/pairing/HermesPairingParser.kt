package app.hermes.mobile.core.pairing

import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64
import java.util.UUID

object HermesPairingParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawUri: String): PairingValidationResult {
        try {
            val uri = URI(rawUri)
            if (uri.scheme != "hermes" || uri.host != "pair") {
                return PairingValidationResult.InvalidPayload("Invalid scheme or host")
            }

            val query = uri.query
            val dataParams = query?.split("&")?.map { it.split("=") }?.firstOrNull { it[0] == "data" }
            val data = if (dataParams != null && dataParams.size > 1) dataParams[1] else null
            if (data == null) {
                return PairingValidationResult.InvalidPayload("Missing data parameter")
            }

            val decodedBytes = try {
                Base64.getUrlDecoder().decode(data)
            } catch (e: IllegalArgumentException) {
                return PairingValidationResult.InvalidPayload("Malformed Base64")
            }

            val jsonString = String(decodedBytes, Charsets.UTF_8)
            val payload = try {
                json.decodeFromString<HermesPairingPayload>(jsonString)
            } catch (e: Exception) {
                return PairingValidationResult.InvalidPayload("Invalid JSON payload")
            }

            if (payload.v != 1) {
                return PairingValidationResult.InvalidVersion(payload.v)
            }
            if (payload.type != "hermes-pair") {
                return PairingValidationResult.InvalidPayload("Invalid type")
            }
            try {
                UUID.fromString(payload.hostId)
            } catch (e: IllegalArgumentException) {
                return PairingValidationResult.InvalidPayload("Invalid host_id")
            }
            if (payload.host.isBlank()) {
                return PairingValidationResult.InvalidPayload("Host is empty")
            }
            if (payload.port !in 1..65535) {
                return PairingValidationResult.InvalidPayload("Invalid port")
            }
            if (payload.scheme != "http" && payload.scheme != "https") {
                return PairingValidationResult.InvalidScheme("Scheme must be http or https")
            }

            if (payload.expiresAt <= System.currentTimeMillis() / 1000) {
                return PairingValidationResult.Expired(payload.expiresAt)
            }

            return PairingValidationResult.Success(payload)
        } catch (e: Exception) {
            return PairingValidationResult.InvalidPayload("Unknown error: ${e.message}")
        }
    }
}
