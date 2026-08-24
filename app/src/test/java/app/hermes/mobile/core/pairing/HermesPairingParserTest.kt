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
        val futureTime = (System.currentTimeMillis() / 1000) + 300
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
                "nonce": "QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"
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
    fun testCanonicalCrossContractFixture() {
        val futureTime = (System.currentTimeMillis() / 1000) + 300
        val json = """
            {
                "v": 1,
                "type": "hermes-pair",
                "host_id": "58af1471-a0a2-4e2b-9426-5068f2a2deab",
                "name": "Office-PC",
                "host": "192.168.1.150",
                "port": 9119,
                "scheme": "http",
                "expires_at": $futureTime,
                "nonce": "QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"
            }
        """.trimIndent()
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue(result is PairingValidationResult.Success)
        val payload = (result as PairingValidationResult.Success).payload
        assertEquals("58af1471-a0a2-4e2b-9426-5068f2a2deab", payload.hostId)
        assertEquals("Office-PC", payload.name)
        assertEquals("192.168.1.150", payload.host)
        assertEquals(9119, payload.port)
        assertEquals("http", payload.scheme)
        assertEquals("QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY", payload.nonce)
    }

    @Test
    fun testExpiredPayloadRejection() {
        val pastTime = (System.currentTimeMillis() / 1000) - 35
        val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":$pastTime,"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        val uri = "hermes://pair?data=${encodePayload(json)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue(result is PairingValidationResult.Expired)
    }

    @Test
    fun testExcessiveTTLRejection() {
        val farFuture = (System.currentTimeMillis() / 1000) + 605
        val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":$farFuture,"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        val result = HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}")
        assertTrue(result is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testInvalidVersionRejection() {
        val json = """{"v":2,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidVersion)
    }

    @Test
    fun testInvalidTypeRejection() {
        val json = """{"v":1,"type":"other","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testInvalidHostIdRejection() {
        val json = """{"v":1,"type":"hermes-pair","host_id":"not-a-uuid","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testInvalidNameRejections() {
        val futures = listOf("", "   ", "Name\u0000", "A".repeat(129))
        futures.forEach { name ->
            val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"$name","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
            assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
        }
    }

    @Test
    fun testInvalidHostRejections() {
        val hosts = listOf("1.1.1.1/path", "user@1.1.1.1", "1.1.1.1:9119", "1.1.1.1?q=1", "1.1.1.1#frag", "1 1")
        hosts.forEach { host ->
            val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"$host","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
            assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
        }
    }

    @Test
    fun testInvalidPortRejections() {
        val ports = listOf(0, 70000)
        ports.forEach { port ->
            val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":$port,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
            assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
        }
    }

    @Test
    fun testInvalidSchemeRejection() {
        val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"ftp","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidScheme)
    }

    @Test
    fun testInvalidNonceRejections() {
        val shortNonce = Base64.getUrlEncoder().withoutPadding().encodeToString("12345".toByteArray())
        val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"$shortNonce"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json)}") is PairingValidationResult.InvalidPayload)
        
        val invalidBase64 = "ThisIs!Not!Valid!Base64"
        val json2 = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"S","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"$invalidBase64"}"""
        assertTrue(HermesPairingParser.parse("hermes://pair?data=${encodePayload(json2)}") is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testOversizedPayloads() {
        val longString = "A".repeat(3000)
        val json = """{"v":1,"type":"hermes-pair","host_id":"58af1471-a0a2-4e2b-9426-5068f2a2deab","name":"$longString","host":"1.1.1.1","port":9119,"scheme":"http","expires_at":${(System.currentTimeMillis() / 1000) + 300},"nonce":"QUJDREVGR0hJSktMTU5PUHFyc3R1dnd4eXoxMjM0NTY"}"""
        val uri = "hermes://pair?data=${encodePayload(json)}"
        assertTrue(HermesPairingParser.parse(uri) is PairingValidationResult.InvalidPayload)
        
        val hugeUri = "hermes://pair?data=" + "A".repeat(5000)
        assertTrue(HermesPairingParser.parse(hugeUri) is PairingValidationResult.InvalidPayload)
    }

    @Test
    fun testMalformedBase64Rejection() {
        val uri = "hermes://pair?data=ThisIs!Not!Valid!Base64"
        val result = HermesPairingParser.parse(uri)
        assertTrue(result is PairingValidationResult.InvalidPayload)
    }
}
