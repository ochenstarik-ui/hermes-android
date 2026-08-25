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

    private fun encodePayloadWith16ByteNonce(
        hostId: String = UUID.randomUUID().toString(),
        name: String = "My Server",
        host: String = "192.168.1.5",
        port: Int = 9119,
        scheme: String = "http",
        expiresAt: Long = (System.currentTimeMillis() / 1000) + 300,
        nonce: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { it.toByte() }),
        v: Int = 1,
        type: String = "hermes-pair",
        fingerprint: String? = null
    ): String {
        val fpField = if (fingerprint != null) ",\n                \"fingerprint\": \"$fingerprint\"" else ""
        val json = """
            {
                "v": $v,
                "type": "$type",
                "host_id": "$hostId",
                "name": "$name",
                "host": "$host",
                "port": $port,
                "scheme": "$scheme",
                "expires_at": $expiresAt,
                "nonce": "$nonce"$fpField
            }
        """.trimIndent()
        return encodePayload(json)
    }

    @Test
    fun testValidPairingPayloadParsing() {
        val futureTime = (System.currentTimeMillis() / 1000) + 300
        val hostId = UUID.randomUUID().toString()
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 0x42 })
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(hostId = hostId, expiresAt = futureTime, nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)

        assertTrue("Expected PairingResult.Success, got $result", result is PairingResult.Success)
        val payload = (result as PairingResult.Success).payload
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
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { (it + 1).toByte() })
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(
            hostId = "58af1471-a0a2-4e2b-9426-5068f2a2deab",
            name = "Office-PC",
            host = "192.168.1.150",
            port = 9119,
            scheme = "http",
            expiresAt = futureTime,
            nonce = nonce16
        )}"
        val result = HermesPairingParser.parse(uri)
        assertTrue(result is PairingResult.Success)
        val payload = (result as PairingResult.Success).payload
        assertEquals("58af1471-a0a2-4e2b-9426-5068f2a2deab", payload.hostId)
        assertEquals("Office-PC", payload.name)
        assertEquals("192.168.1.150", payload.host)
        assertEquals(9119, payload.port)
        assertEquals("http", payload.scheme)
        assertEquals(nonce16, payload.nonce)
    }

    @Test
    fun testExpiredPayloadRejection() {
        val pastTime = (System.currentTimeMillis() / 1000) - 35
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(expiresAt = pastTime, nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected ExpiredPayload, got $result", result is PairingResult.Failure && result.error is PairingError.ExpiredPayload)
    }

    @Test
    fun testInvalidVersionRejection() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(v = 3, nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected UnsupportedProtocolVersion, got $result", result is PairingResult.Failure && result.error is PairingError.UnsupportedProtocolVersion)
    }

    @Test
    fun testValidV2PayloadWithFingerprint() {
        val futureTime = (System.currentTimeMillis() / 1000) + 300
        val hostId = UUID.randomUUID().toString()
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { 0x42 })
        val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(
            v = 2,
            hostId = hostId,
            scheme = "https",
            expiresAt = futureTime,
            nonce = nonce16,
            fingerprint = fp
        )}"
        val result = HermesPairingParser.parse(uri)

        assertTrue("Expected PairingResult.Success, got $result", result is PairingResult.Success)
        val payload = (result as PairingResult.Success).payload
        assertEquals(2, payload.v)
        assertEquals(hostId, payload.hostId)
        assertEquals("https", payload.scheme)
        assertEquals(fp, payload.fingerprint)
    }

    @Test
    fun testInvalidFingerprintRejection() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val badFps = listOf("not_hex_at_all", "AA:BB", "A".repeat(63), "A".repeat(65))
        badFps.forEach { badFp ->
            val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(
                v = 2,
                scheme = "https",
                nonce = nonce16,
                fingerprint = badFp
            )}"
            val result = HermesPairingParser.parse(uri)
            assertTrue("Expected InvalidFingerprint for '$badFp', got $result", result is PairingResult.Failure && result.error is PairingError.InvalidFingerprint)
        }
    }

    @Test
    fun testInvalidTypeRejection() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(type = "other", nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected InvalidPayloadType, got $result", result is PairingResult.Failure && result.error is PairingError.InvalidPayloadType)
    }

    @Test
    fun testInvalidHostIdRejection() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(hostId = "not-a-uuid", nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected InvalidHostId, got $result", result is PairingResult.Failure && result.error is PairingError.InvalidHostId)
    }

    @Test
    fun testInvalidNameRejections() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val badNames = listOf("", "   ", "Name\u0000", "A".repeat(129))
        badNames.forEach { name ->
            val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(name = name, nonce = nonce16)}"
            val result = HermesPairingParser.parse(uri)
            assertTrue("Expected failure for name '$name', got $result", result is PairingResult.Failure && result.error is PairingError.InvalidName)
        }
    }

    @Test
    fun testInvalidHostRejections() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val hosts = listOf("1.1.1.1/path", "user@1.1.1.1", "1.1.1.1:9119", "1.1.1.1?q=1", "1.1.1.1#frag", "1 1")
        hosts.forEach { host ->
            val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(host = host, nonce = nonce16)}"
            val result = HermesPairingParser.parse(uri)
            assertTrue("Expected failure for host '$host', got $result", result is PairingResult.Failure && (result.error is PairingError.InvalidHost || result.error is PairingError.EmptyHost))
        }
    }

    @Test
    fun testInvalidPortRejections() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val ports = listOf(0, 70000)
        ports.forEach { port ->
            val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(port = port, nonce = nonce16)}"
            val result = HermesPairingParser.parse(uri)
            assertTrue("Expected InvalidPort for port $port, got $result", result is PairingResult.Failure && result.error is PairingError.InvalidPort)
        }
    }

    @Test
    fun testInvalidSchemeRejection() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(scheme = "ftp", nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected InvalidScheme, got $result", result is PairingResult.Failure && result.error is PairingError.InvalidScheme)
    }

    @Test
    fun testInvalidNonceRejections() {
        val shortNonce = Base64.getUrlEncoder().withoutPadding().encodeToString("12345".toByteArray())
        val uri1 = "hermes://pair?data=${encodePayloadWith16ByteNonce(nonce = shortNonce)}"
        val result1 = HermesPairingParser.parse(uri1)
        assertTrue("Expected InvalidNonce for short nonce, got $result1", result1 is PairingResult.Failure && result1.error is PairingError.InvalidNonce)

        val invalidBase64 = "ThisIs!Not!Valid!Base64"
        val uri2 = "hermes://pair?data=${encodePayloadWith16ByteNonce(nonce = invalidBase64)}"
        val result2 = HermesPairingParser.parse(uri2)
        assertTrue("Expected InvalidNonce for bad b64 nonce, got $result2", result2 is PairingResult.Failure && result2.error is PairingError.InvalidNonce)
    }

    @Test
    fun testOversizedPayloads() {
        val nonce16 = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))
        val longString = "A".repeat(3000)
        val uri = "hermes://pair?data=${encodePayloadWith16ByteNonce(name = longString, nonce = nonce16)}"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected failure for oversized name, got $result", result is PairingResult.Failure)

        val hugeUri = "hermes://pair?data=" + "A".repeat(5000)
        val result2 = HermesPairingParser.parse(hugeUri)
        assertTrue("Expected failure for huge URI, got $result2", result2 is PairingResult.Failure)
    }

    @Test
    fun testMalformedBase64Rejection() {
        val uri = "hermes://pair?data=ThisIs!Not!Valid!Base64"
        val result = HermesPairingParser.parse(uri)
        assertTrue("Expected Base64DecodeError, got $result", result is PairingResult.Failure && result.error is PairingError.Base64DecodeError)
    }
}
