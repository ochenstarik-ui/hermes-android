package app.hermes.mobile.core.pairing

import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64
import java.util.UUID

object HermesPairingParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawUri: String): PairingValidationResult {
        try {
            if (rawUri.toByteArray(Charsets.UTF_8).size > 4096) {
                return PairingValidationResult.InvalidPayload("URI exceeds maximum length of 4096 bytes")
            }

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

            if (decodedBytes.size > 2048) {
                return PairingValidationResult.InvalidPayload("Decoded payload exceeds 2048 bytes")
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
                val uuid = UUID.fromString(payload.hostId)
                if (uuid.toString() != payload.hostId.lowercase()) {
                    return PairingValidationResult.InvalidPayload("host_id must be a valid UUID string")
                }
            } catch (e: IllegalArgumentException) {
                return PairingValidationResult.InvalidPayload("Invalid host_id")
            }
            
            if (payload.name.isBlank()) {
                return PairingValidationResult.InvalidPayload("Name is empty")
            }
            val trimmedName = payload.name.trim()
            if (trimmedName.length > 128) {
                return PairingValidationResult.InvalidPayload("Name exceeds 128 characters")
            }
            if (payload.name.any { it in '\u0000'..'\u001F' || it in '\u007F'..'\u009F' }) {
                return PairingValidationResult.InvalidPayload("Name contains control characters")
            }

            if (payload.host.isBlank()) {
                return PairingValidationResult.InvalidPayload("Host is empty")
            }
            if (payload.host.any { it.isWhitespace() || it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' || it == ':' || it in '\u0000'..'\u001F' || it in '\u007F'..'\u009F' }) {
                return PairingValidationResult.InvalidPayload("Host contains invalid characters")
            }

            if (payload.port !in 1..65535) {
                return PairingValidationResult.InvalidPayload("Invalid port")
            }
            if (payload.scheme != "http" && payload.scheme != "https") {
                return PairingValidationResult.InvalidScheme("Scheme must be http or https")
            }

            if (payload.nonce.isBlank()) {
                return PairingValidationResult.InvalidPayload("Nonce is empty")
            }
            val nonceBytes = try {
                Base64.getUrlDecoder().decode(payload.nonce)
            } catch (e: IllegalArgumentException) {
                return PairingValidationResult.InvalidPayload("Nonce must be valid Base64URL")
            }
            if (nonceBytes.size < 16 || nonceBytes.size > 64) {
                return PairingValidationResult.InvalidPayload("Nonce must decode to between 16 and 64 bytes")
            }

            val now = System.currentTimeMillis() / 1000
            if (payload.expiresAt < now - 30) {
                return PairingValidationResult.Expired(payload.expiresAt)
            }
            if (payload.expiresAt > now + 600) {
                return PairingValidationResult.InvalidPayload("Expiry exceeds maximum TTL of 600 seconds")
            }

            return PairingValidationResult.Success(payload)
        } catch (e: Exception) {
            return PairingValidationResult.InvalidPayload("Unknown error: ${e.message}")
        }
    }
}
