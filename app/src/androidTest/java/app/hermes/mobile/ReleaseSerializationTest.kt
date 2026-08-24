package app.hermes.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermes.mobile.core.model.BindingState
import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.HermesApproval
import app.hermes.mobile.core.model.HermesClarifyRequest
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostAttributedApproval
import app.hermes.mobile.core.model.HostAttributedClarify
import app.hermes.mobile.core.model.HostSessionBinding
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.RuntimeSessionId
import app.hermes.mobile.core.model.ToolActivity
import app.hermes.mobile.core.model.UnifiedMessage
import app.hermes.mobile.core.model.UnifiedMessageSource
import app.hermes.mobile.core.model.UnifiedSession
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.pairing.CanonicalEndpoint
import app.hermes.mobile.core.pairing.HermesPairingPayload
import app.hermes.mobile.core.security.NativeAuthTokens
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun testDomainModelSerializationOnDevice() {
        val host = HermesHost(
            id = HermesHostId("h-release-1"),
            displayName = "Release Host",
            baseUrl = "https://10.0.2.2:9119",
            allowCleartext = false,
            enabled = true,
            lastSeenAt = 123456789L,
            lastKnownStatus = HostStatus.ONLINE,
            certificateFingerprint = "SHA256:abcd"
        )
        val serializedHost = json.encodeToString(host)
        val deserializedHost = json.decodeFromString<HermesHost>(serializedHost)
        assertEquals(host, deserializedHost)

        val tokens = NativeAuthTokens(
            accessToken = "access-secret",
            refreshToken = "refresh-secret",
            tokenType = "Bearer",
            expiresIn = 3600L,
            scope = "read write"
        )
        val serializedTokens = json.encodeToString(tokens)
        val deserializedTokens = json.decodeFromString<NativeAuthTokens>(serializedTokens)
        assertEquals("access-secret", deserializedTokens.accessToken)

        val session = UnifiedSession(
            id = UnifiedSessionId("sess-device"),
            title = "Device Test Session",
            activeHostId = HermesHostId("h-release-1"),
            timeline = listOf(
                UnifiedMessage(
                    id = "msg-device-1",
                    role = MessageRole.USER,
                    content = "Hello from Android instrumentation test",
                    source = UnifiedMessageSource.USER
                )
            )
        )
        val serializedSession = json.encodeToString(session)
        val deserializedSession = json.decodeFromString<UnifiedSession>(serializedSession)
        assertEquals(session.id, deserializedSession.id)
        assertEquals(1, deserializedSession.timeline.size)
    }

    @Test
    fun testPairingAndGatewayEventSerializationOnDevice() {
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = "550e8400-e29b-41d4-a716-446655440000",
            name = "Android Test Server",
            host = "10.0.2.2",
            port = 9119,
            scheme = "https",
            expiresAt = 1700000000000L,
            nonce = "abcdef1234567890"
        )
        val serializedPayload = json.encodeToString(payload)
        val deserializedPayload = json.decodeFromString<HermesPairingPayload>(serializedPayload)
        assertEquals(payload, deserializedPayload)

        val rawReady = buildJsonObject {
            put("type", "gateway.ready")
            put("version", "1.5.0")
            put("session_count", 3)
        }
        val event = GatewayEvent.parse(rawReady)
        assertNotNull(event)
        assertTrue(event is GatewayEvent.GatewayReadyEvent)
    }
}
