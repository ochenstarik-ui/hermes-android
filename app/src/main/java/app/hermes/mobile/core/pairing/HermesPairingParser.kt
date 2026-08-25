package app.hermes.mobile.core.pairing

import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

object HermesPairingParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun parse(rawUri: String, currentTimeSeconds: Long = System.currentTimeMillis() / 1000): PairingResult {
        if (rawUri.toByteArray(Charsets.UTF_8).size > 4096) {
            return PairingResult.Failure(PairingError.PayloadTooLarge("URI exceeds maximum length of 4096 bytes"))
        }

        val trimmedUri = rawUri.trim()
        val isValidPrefix = trimmedUri.startsWith("hermes://pair?", ignoreCase = true) ||
                trimmedUri.startsWith("hermes:/pair?", ignoreCase = true) ||
                trimmedUri.equals("hermes://pair", ignoreCase = true) ||
                trimmedUri.equals("hermes:/pair", ignoreCase = true)

        if (!isValidPrefix) {
            return PairingResult.Failure(PairingError.InvalidUriFormat("URI does not match hermes://pair or hermes:/pair"))
        }

        val queryIndex = trimmedUri.indexOf('?')
        if (queryIndex == -1) {
            return PairingResult.Failure(PairingError.MissingDataParam("Missing 'data' query parameter"))
        }
        val queryString = trimmedUri.substring(queryIndex + 1)

        var dataParamValue: String? = null
        for (segment in queryString.split('&')) {
            if (segment.isEmpty()) continue
            val equalsIndex = segment.indexOf('=')
            val key: String
            val value: String
            if (equalsIndex != -1) {
                key = URLDecoder.decode(segment.substring(0, equalsIndex), "UTF-8")
                value = URLDecoder.decode(segment.substring(equalsIndex + 1), "UTF-8")
            } else {
                key = URLDecoder.decode(segment, "UTF-8")
                value = ""
            }
            if (key == "data") {
                dataParamValue = value
                break
            }
        }

        if (dataParamValue == null) {
            return PairingResult.Failure(PairingError.MissingDataParam("Missing 'data' query parameter"))
        }
        if (dataParamValue.isEmpty()) {
            return PairingResult.Failure(PairingError.EmptyData("Empty 'data' query parameter"))
        }

        val decodedBytes = try {
            Base64.getUrlDecoder().decode(dataParamValue)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getDecoder().decode(dataParamValue)
            } catch (_: IllegalArgumentException) {
                return PairingResult.Failure(PairingError.Base64DecodeError("Failed to decode Base64 data"))
            }
        }

        if (decodedBytes.size > 2048) {
            return PairingResult.Failure(PairingError.PayloadTooLarge("Decoded payload exceeds 2048 bytes"))
        }

        val jsonString = String(decodedBytes, Charsets.UTF_8)
        val payload = try {
            json.decodeFromString<PairingPayloadV1>(jsonString)
        } catch (_: Exception) {
            return PairingResult.Failure(PairingError.JsonSyntaxError("Malformed JSON payload"))
        }

        if (payload.v != 1 && payload.v != 2) {
            return PairingResult.Failure(PairingError.UnsupportedProtocolVersion(payload.v))
        }
        if (payload.type != "hermes-pair") {
            return PairingResult.Failure(PairingError.InvalidPayloadType(payload.type))
        }

        if (payload.fingerprint != null) {
            val rawFp = payload.fingerprint.trim()
            if (rawFp.isEmpty()) {
                return PairingResult.Failure(PairingError.InvalidFingerprint("Certificate fingerprint cannot be empty"))
            }
            val cleanFp = rawFp
                .removePrefix("SHA256:")
                .removePrefix("sha256:")
                .removePrefix("SHA-256:")
                .removePrefix("sha-256:")
                .replace(":", "")
                .replace(" ", "")
                .replace("-", "")

            if (cleanFp.length != 64 || !cleanFp.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return PairingResult.Failure(PairingError.InvalidFingerprint("Invalid certificate fingerprint: expected 64 hex characters (SHA-256)"))
            }
        }

        try {
            UUID.fromString(payload.hostId)
            if (!uuidRegex.matches(payload.hostId)) {
                return PairingResult.Failure(PairingError.InvalidHostId(payload.hostId))
            }
        } catch (_: IllegalArgumentException) {
            return PairingResult.Failure(PairingError.InvalidHostId(payload.hostId))
        }

        if (payload.name.isBlank()) {
            return PairingResult.Failure(PairingError.InvalidName("Name cannot be blank"))
        }
        val trimmedName = payload.name.trim()
        if (trimmedName.length > 128) {
            return PairingResult.Failure(PairingError.InvalidName("Name exceeds 128 characters"))
        }
        if (payload.name.any { it in '\u0000'..'\u001F' || it in '\u007F'..'\u009F' }) {
            return PairingResult.Failure(PairingError.InvalidName("Name contains control characters"))
        }

        if (payload.host.isBlank()) {
            return PairingResult.Failure(PairingError.EmptyHost("Host address cannot be empty"))
        }
        val hostTrimmed = payload.host.trim()
        if (hostTrimmed.any { it.isWhitespace() || it == '/' || it == '\\' || it == '?' || it == '#' || it == '@' || it in '\u0000'..'\u001F' || it in '\u007F'..'\u009F' }) {
            return PairingResult.Failure(PairingError.InvalidHost("Host contains invalid characters"))
        }
        if (!hostTrimmed.startsWith("[") || !hostTrimmed.endsWith("]")) {
            if (hostTrimmed.contains(":")) {
                return PairingResult.Failure(PairingError.InvalidHost("Port must not be included in host field"))
            }
        }

        if (payload.port !in 1..65535) {
            return PairingResult.Failure(PairingError.InvalidPort(payload.port))
        }

        val schemeLower = payload.scheme.lowercase()
        if (schemeLower != "http" && schemeLower != "https") {
            return PairingResult.Failure(PairingError.InvalidScheme(payload.scheme))
        }

        if (payload.nonce.isBlank()) {
            return PairingResult.Failure(PairingError.InvalidNonce("Nonce is empty"))
        }
        val nonceBytes = try {
            Base64.getUrlDecoder().decode(payload.nonce)
        } catch (_: IllegalArgumentException) {
            try {
                Base64.getDecoder().decode(payload.nonce)
            } catch (_: IllegalArgumentException) {
                return PairingResult.Failure(PairingError.InvalidNonce("Nonce is not valid Base64"))
            }
        }
        if (nonceBytes.size != 16) {
            return PairingResult.Failure(PairingError.InvalidNonce("Nonce length is ${nonceBytes.size} bytes, expected 16 bytes"))
        }

        if (payload.expiresAt < currentTimeSeconds - 30) {
            return PairingResult.Failure(PairingError.ExpiredPayload(payload.expiresAt))
        }

        return PairingResult.Success(payload)
    }
}
