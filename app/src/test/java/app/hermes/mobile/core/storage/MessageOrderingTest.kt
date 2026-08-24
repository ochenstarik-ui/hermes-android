package app.hermes.mobile.core.storage

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageOrderingTest {

    @Test
    fun testDeterministicOrderingWithIdenticalTimestamps() = runTest {
        val sessionDao = FakeUnifiedSessionDao()
        val sessionId = "session-test-order"

        sessionDao.insertSession(
            UnifiedSessionEntity(
                id = sessionId,
                title = "Order Test",
                activeHostId = "host-1",
                createdAt = 100L,
                updatedAt = 100L
            )
        )

        // Insert messages out of order:
        // msg-c (1000ms), msg-a (1000ms), msg-b (1000ms), msg-0 (500ms)
        val msgC = UnifiedMessageEntity(id = "msg-c", sessionId = sessionId, role = "USER", content = "C", createdAt = 1000L)
        val msgA = UnifiedMessageEntity(id = "msg-a", sessionId = sessionId, role = "ASSISTANT", content = "A", createdAt = 1000L)
        val msgB = UnifiedMessageEntity(id = "msg-b", sessionId = sessionId, role = "USER", content = "B", createdAt = 1000L)
        val msg0 = UnifiedMessageEntity(id = "msg-0", sessionId = sessionId, role = "SYSTEM", content = "0", createdAt = 500L)

        sessionDao.insertMessages(listOf(msgC, msgA, msgB, msg0))

        val fromDetails = sessionDao.getSessionWithDetails(sessionId)?.messages?.map { it.id }
        val fromMessages = sessionDao.getMessagesForSession(sessionId).map { it.id }

        val expected = listOf("msg-0", "msg-a", "msg-b", "msg-c")

        assertEquals("getSessionWithDetails must order by createdAt ASC, id ASC", expected, fromDetails)
        assertEquals("getMessagesForSession must order by createdAt ASC, id ASC", expected, fromMessages)
    }

    @Test
    fun testRepositoryDeterministicOrderingAcrossReadPaths() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val hostDao = FakeHostDao()
        val sessionDao = FakeUnifiedSessionDao()
        val tokenVault = InMemoryTokenVault()
        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher)
        )
        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = CoroutineScope(testDispatcher)
        )

        val host1 = HermesHost(id = HermesHostId("host-1"), displayName = "Host 1", baseUrl = "http://host1:9119")
        connectionManager.addHost(host1)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession(title = "Order Test", initialHostId = host1.id)
        testScheduler.advanceUntilIdle()

        val msgZ = UnifiedMessageEntity(id = "msg-z", sessionId = session.id.value, role = "USER", content = "Z", createdAt = 2000L)
        val msgB = UnifiedMessageEntity(id = "msg-b", sessionId = session.id.value, role = "USER", content = "B", createdAt = 2000L)
        val msgA = UnifiedMessageEntity(id = "msg-a", sessionId = session.id.value, role = "ASSISTANT", content = "A", createdAt = 2000L)

        sessionDao.insertMessages(listOf(msgZ, msgB, msgA))
        testScheduler.advanceUntilIdle()

        val expected = listOf("msg-a", "msg-b", "msg-z")

        val fetchedSession = repository.getUnifiedSession(session.id)
        val timelineIds = fetchedSession?.timeline?.map { it.id }
        assertEquals("Repository getUnifiedSession must order messages by createdAt ASC, id ASC", expected, timelineIds)

        val sessionFromList = repository.sessions.value.find { it.id == session.id }
        val sessionListTimelineIds = sessionFromList?.timeline?.map { it.id }
        assertEquals("Repository sessions flow must order messages by createdAt ASC, id ASC", expected, sessionListTimelineIds)
    }
}
