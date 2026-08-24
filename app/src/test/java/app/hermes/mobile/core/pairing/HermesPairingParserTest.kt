package app.hermes.mobile.core.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

class HermesPairingParserTest {

    private fun encodePayload(json: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    @Test
    fun testValidPairingPayloadParsing() {
        val futureTime = (System.currentTimeMillis() / 1000) + 3600
        val hostId = UUID.randomUUID().toString()
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "host_id": "$hostId",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 9119,
                "scheme": "http",
                "expires_at": $futureTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.Success)
        val payload = (result as PairingValidationResult.Success).payload
        assertEquals(1, payload.v)
        assertEquals(hostId, payload.hostId)
        assertEquals("My Server", payload.name)
        assertEquals("192.168.1.5", payload.host)
        assertEquals(9119, payload.port)
        assertEquals("http", payload.scheme)
    }

    @Test
    fun testExpiredPayloadRejection() {
        val pastTime = (System.currentTimeMillis() / 1000) - 3600
        val hostId = UUID.randomUUID().toString()
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "host_id": "$hostId",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 9119,
                "scheme": "http",
                "expires_at": $pastTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.Expired)
    }

    @Test
    fun testInvalidVersionRejection() {
        val futureTime = (System.currentTimeMillis() / 1000) + 3600
        val hostId = UUID.randomUUID().toString()
        val json = """
            {
                "v": 2,
                "type": "hermes-pair",
                "host_id": "$hostId",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 9119,
                "scheme": "http",
                "expires_at": $futureTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.InvalidVersion)
    }

    @Test
    fun testMalformedBase64Rejection() {
        val uri = "hermes://pair?data=ThisIs!Not!Valid!Base64"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testInvalidPortRejection() {
        val futureTime = (System.currentTimeMillis() / 1000) + 3600
        val hostId = UUID.randomUUID().toString()
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "host_id": "$hostId",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 70000,
                "scheme": "http",
                "expires_at": $futureTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testInvalidSchemeRejection() {
        val futureTime = (System.currentTimeMillis() / 1000) + 3600
        val hostId = UUID.randomUUID().toString()
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "host_id": "$hostId",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 9119,
                "scheme": "ftp",
                "expires_at": $futureTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.InvalidScheme)
    }

    @Test
    fun testMissingHostIdRejection() {
        val futureTime = (System.currentTimeMillis() / 1000) + 3600
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "name": "My Server",
                "host": "192.168.1.5",
                "port": 9119,
                "scheme": "http",
                "expires_at": $futureTime,
                "nonce": "random-nonce"
            }
        """.trimIndent()
        
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        
        assertTrue(result is PairingValidationResult.InvalidPayload)
    }
}
