package app.hermes.mobile.core.sync

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.ToolActivity
import app.hermes.mobile.core.model.UnifiedMessage
import app.hermes.mobile.core.model.UnifiedMessageSource
import app.hermes.mobile.core.model.UnifiedSession
import app.hermes.mobile.core.model.UnifiedSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedContextBuilderTest {

    @Test
    fun testSecretRedaction() {
        val input = "Here is my token Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeak and sk-1234567890abcdef1234567890 and password=mySuperSecret123!"
        val sanitized = UnifiedContextBuilder.sanitizeContent(input)

        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(sanitized.contains("sk-1234567890abcdef1234567890"))
        assertFalse(sanitized.contains("mySuperSecret123!"))
        assertTrue(sanitized.contains("[REDACTED_"))
    }

    @Test
    fun testEmptySessionSync() {
        val targetHost = HermesHost(
            id = HermesHostId("host-2"),
            displayName = "Linux Server",
            baseUrl = "http://192.168.1.100:9119"
        )
        val session = UnifiedSession(
            id = UnifiedSessionId("session-1"),
            title = "Test Session",
            activeHostId = HermesHostId("host-1"),
            timeline = emptyList()
        )

        val result = UnifiedContextBuilder.buildContextSyncPayload(session, targetHost)
        assertFalse(result.hasNewContext)
        assertEquals("", result.contextPrompt)
        assertEquals(null, result.latestSyncedMessageId)
    }

    @Test
    fun testDeltaGeneration() {
        val host1Id = HermesHostId("host-1")
        val host2Id = HermesHostId("host-2")

        val host1 = HermesHost(id = host1Id, displayName = "Office PC", baseUrl = "http://192.168.1.50:9119")
        val host2 = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://192.168.1.100:9119")
        val hostsMap = mapOf(host1Id to host1, host2Id to host2)

        val msg1 = UnifiedMessage(
            id = "msg-1",
            role = MessageRole.USER,
            content = "Write a python script to parse CSV files.",
            source = UnifiedMessageSource.USER
        )
        val msg2 = UnifiedMessage(
            id = "msg-2",
            role = MessageRole.ASSISTANT,
            content = "Sure! Here is the python script using pandas...",
            hostId = host1Id,
            tools = listOf(ToolActivity("t1", "fs_read", "completed")),
            source = UnifiedMessageSource.HERMES
        )
        val msg3 = UnifiedMessage(
            id = "msg-3",
            role = MessageRole.USER,
            content = "Now run it on the linux server dataset.",
            source = UnifiedMessageSource.USER
        )

        val session = UnifiedSession(
            id = UnifiedSessionId("session-1"),
            title = "Python Data Analysis",
            activeHostId = host2Id,
            timeline = listOf(msg1, msg2, msg3)
        )

        // Case 1: Brand new host binding (syncedThroughMessageId is null)
        val syncAll = UnifiedContextBuilder.buildContextSyncPayload(session, host2, hostsMap, null)
        assertTrue(syncAll.hasNewContext)
        assertEquals("msg-3", syncAll.latestSyncedMessageId)
        assertTrue(syncAll.contextPrompt.contains("[Unified Hermes Session Context Transfer]"))
        assertTrue(syncAll.contextPrompt.contains("Office PC"))
        assertTrue(syncAll.contextPrompt.contains("Write a python script"))
        assertTrue(syncAll.contextPrompt.contains("Linux Server"))
        assertFalse(syncAll.contextPrompt.contains("192.168.1.100:9119"))
        assertFalse(syncAll.contextPrompt.contains("192.168.1.50:9119"))

        // Case 2: Stale host binding (synced up to msg-1, needs delta msg-2 and msg-3)
        val syncDelta = UnifiedContextBuilder.buildContextSyncPayload(session, host2, hostsMap, "msg-1")
        assertTrue(syncDelta.hasNewContext)
        assertEquals("msg-3", syncDelta.latestSyncedMessageId)
        assertFalse(syncDelta.contextPrompt.contains("Write a python script to parse CSV files."))
        assertTrue(syncDelta.contextPrompt.contains("Sure! Here is the python script"))
        assertTrue(syncDelta.contextPrompt.contains("Now run it on the linux server dataset."))
        assertFalse(syncDelta.contextPrompt.contains("192.168.1.100:9119"))

        // Case 3: Fully synced host binding (synced up to msg-3)
        val syncUpToDate = UnifiedContextBuilder.buildContextSyncPayload(session, host2, hostsMap, "msg-3")
        assertFalse(syncUpToDate.hasNewContext)
        assertEquals("", syncUpToDate.contextPrompt)
        assertEquals("msg-3", syncUpToDate.latestSyncedMessageId)
    }

}
