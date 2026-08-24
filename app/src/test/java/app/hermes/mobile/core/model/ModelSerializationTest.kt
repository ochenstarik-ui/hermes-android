package app.hermes.mobile.core.model

import app.hermes.mobile.core.pairing.CanonicalEndpoint
import app.hermes.mobile.core.pairing.HermesPairingPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun testHermesHostSerialization() {
        val host = HermesHost(
            id = HermesHostId("host-123"),
            displayName = "Mac Studio",
            baseUrl = "https://192.168.1.50:9119",
            allowCleartext = false,
            enabled = true,
            lastSeenAt = 1700000000000L,
            lastKnownStatus = HostStatus.ONLINE,
            certificateFingerprint = "SHA256:abcd1234"
        )
        val serialized = json.encodeToString(host)
        val deserialized = json.decodeFromString<HermesHost>(serialized)
        assertEquals(host, deserialized)
    }

    @Test
    fun testUnifiedSessionAndMessageSerialization() {
        val message = UnifiedMessage(
            id = "msg-1",
            role = MessageRole.ASSISTANT,
            content = "Running command now",
            hostId = HermesHostId("host-1"),
            source = UnifiedMessageSource.HERMES,
            createdAt = 1700000001000L,
            nativeMessageId = "nat-1",
            thinking = "Processing user prompt",
            tools = listOf(
                ToolActivity(
                    id = "tool-1",
                    name = "bash",
                    status = "completed",
                    progress = "100%",
                    result = "file.txt",
                    isError = false
                )
            ),
            isStreaming = false
        )

        val binding = HostSessionBinding(
            hostId = HermesHostId("host-1"),
            durableSessionId = DurableSessionId("dur-1"),
            runtimeSessionId = RuntimeSessionId("run-1"),
            lastAttachedAt = 1700000002000L,
            state = BindingState.READY,
            syncedThroughMessageId = "msg-1",
            syncedAt = 1700000003000L
        )

        val session = UnifiedSession(
            id = UnifiedSessionId("sess-1"),
            title = "Project Workspace",
            activeHostId = HermesHostId("host-1"),
            createdAt = 1700000000000L,
            updatedAt = 1700000004000L,
            bindings = mapOf(HermesHostId("host-1") to binding),
            timeline = listOf(message),
            messageCount = 1,
            lastMessagePreview = "Running command now"
        )

        val serialized = json.encodeToString(session)
        val deserialized = json.decodeFromString<UnifiedSession>(serialized)
        assertEquals(session, deserialized)
        assertEquals("Project Workspace", deserialized.title)
        assertEquals(1, deserialized.timeline.size)
        assertEquals("bash", deserialized.timeline[0].tools[0].name)
    }

    @Test
    fun testApprovalAndClarifySerialization() {
        val approval = HostAttributedApproval(
            hostId = HermesHostId("host-1"),
            hostDisplayName = "Dev Server",
            runtimeSessionId = RuntimeSessionId("rt-1"),
            approval = HermesApproval(
                requestId = "req-1",
                command = "rm -rf /tmp/cache",
                description = "Delete temporary build cache",
                choices = listOf("once", "deny", "always")
            )
        )
        val serializedApproval = json.encodeToString(approval)
        val deserializedApproval = json.decodeFromString<HostAttributedApproval>(serializedApproval)
        assertEquals(approval, deserializedApproval)

        val clarify = HostAttributedClarify(
            hostId = HermesHostId("host-2"),
            hostDisplayName = "Cloud Server",
            runtimeSessionId = RuntimeSessionId("rt-2"),
            request = HermesClarifyRequest(
                requestId = "req-2",
                questionId = "q-1",
                question = "Enter sudo password:",
                promptType = ClarifyType.SUDO
            )
        )
        val serializedClarify = json.encodeToString(clarify)
        val deserializedClarify = json.decodeFromString<HostAttributedClarify>(serializedClarify)
        assertEquals(clarify, deserializedClarify)
    }

    @Test
    fun testPairingPayloadSerialization() {
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = "550e8400-e29b-41d4-a716-446655440000",
            name = "Office Server",
            host = "192.168.1.100",
            port = 9119,
            scheme = "https",
            expiresAt = 1700000000000L,
            nonce = "1234567890abcdef"
        )
        val serialized = json.encodeToString(payload)
        val deserialized = json.decodeFromString<HermesPairingPayload>(serialized)
        assertEquals(payload, deserialized)
    }

    @Test
    fun testGatewayEventParsing() {
        val rawReady = buildJsonObject {
            put("type", "gateway.ready")
            put("version", "1.4.0")
            put("session_count", 5)
        }
        val event = GatewayEvent.parse(rawReady)
        assertNotNull(event)
        assertTrue(event is GatewayEvent.GatewayReadyEvent)
        val ready = event as GatewayEvent.GatewayReadyEvent
        assertEquals("1.4.0", ready.version)
        assertEquals(5, ready.sessionCount)
    }
}
