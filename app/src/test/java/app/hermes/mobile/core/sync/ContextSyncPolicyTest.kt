package app.hermes.mobile.core.sync

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.UnifiedMessage
import app.hermes.mobile.core.model.UnifiedMessageSource
import app.hermes.mobile.core.model.UnifiedSession
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.model.HostSessionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ContextSyncPolicyTest {

    private fun generateMessages(count: Int): List<UnifiedMessage> {
        return (1..count).map { i ->
            UnifiedMessage(
                id = "msg-$i",
                role = MessageRole.USER,
                content = "Message $i",
                hostId = null,
                source = UnifiedMessageSource.USER,
                createdAt = i.toLong()
            )
        }
    }

    @Test
    fun `bounded delta transfer limits missing cursor to max 10 messages`() {
        val messages = generateMessages(15)
        val session = UnifiedSession(
            id = UnifiedSessionId("session-1"),
            title = "Test Session",
            activeHostId = HermesHostId("host-1"),
            timeline = messages,
            bindings = emptyMap()
        )
        val host = HermesHost(HermesHostId("host-1"), "host-1", "http://host1")

        val result = UnifiedContextBuilder.buildContextSyncPayload(
            session = session,
            targetHost = host,
            syncedThroughMessageId = null
        )

        assertTrue(result.hasNewContext)
        // Check that only 10 messages are included.
        assertTrue(result.contextPrompt.contains("Message 6"))
        assertFalse(result.contextPrompt.contains("Message 5"))
    }

    @Test
    fun `sensitive keys and tokens are stripped`() {
        val messages = listOf(
            UnifiedMessage(
                id = "msg-1",
                role = MessageRole.USER,
                content = "Here is my key: sk-abcdefghijklmnopqrstuvw and token Bearer abcdef123",
                hostId = null,
                source = UnifiedMessageSource.USER,
                createdAt = 1L
            )
        )
        val session = UnifiedSession(
            id = UnifiedSessionId("session-1"),
            title = "Test Session",
            activeHostId = HermesHostId("host-1"),
            timeline = messages,
            bindings = emptyMap()
        )
        val host = HermesHost(HermesHostId("host-1"), "host-1", "http://host1")

        val result = UnifiedContextBuilder.buildContextSyncPayload(
            session = session,
            targetHost = host,
            syncedThroughMessageId = null
        )

        assertFalse(result.contextPrompt.contains("sk-abcdefghijklmnopqrstuvw"))
        assertTrue(result.contextPrompt.contains("[REDACTED_API_KEY]"))
        assertFalse(result.contextPrompt.contains("abcdef123"))
        assertTrue(result.contextPrompt.contains("[REDACTED_TOKEN]"))
    }

    @Test
    fun `syncedThroughMessageId is respected but bounded`() {
        val messages = generateMessages(20)
        val session = UnifiedSession(
            id = UnifiedSessionId("session-1"),
            title = "Test Session",
            activeHostId = HermesHostId("host-1"),
            timeline = messages,
            bindings = emptyMap()
        )
        val host = HermesHost(HermesHostId("host-1"), "host-1", "http://host1")

        val result = UnifiedContextBuilder.buildContextSyncPayload(
            session = session,
            targetHost = host,
            syncedThroughMessageId = "msg-2"
        )

        // 18 messages delta, but it should be bounded to 10
        assertTrue(result.hasNewContext)
        assertTrue(result.contextPrompt.contains("Message 11"))
        assertFalse(result.contextPrompt.contains("Message 10"))
    }
}
