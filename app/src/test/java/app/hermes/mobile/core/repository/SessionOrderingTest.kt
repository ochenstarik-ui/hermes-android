package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.UnifiedMessageEntity
import app.hermes.mobile.core.storage.UnifiedSessionEntity
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOrderingTest {

    @Test
    fun testSessionUpdatedAtBumpedOnMessageInsertion() = runBlocking {
        val hostDao = FakeHostDao()
        val sessionDao = FakeUnifiedSessionDao()
        val tokenVault = InMemoryTokenVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val hostId = HermesHostId("host-1")
        val host = HermesHost(id = hostId, displayName = "Host 1", baseUrl = "http://host1:9119")

        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = scope
        )

        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = scope
        )

        connectionManager.addHost(host)

        // Session 1: created earlier (updatedAt = 1000)
        val session1 = UnifiedSessionEntity(
            id = "session-1",
            title = "Session 1",
            activeHostId = hostId.value,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        // Session 2: created later (updatedAt = 2000)
        val session2 = UnifiedSessionEntity(
            id = "session-2",
            title = "Session 2",
            activeHostId = hostId.value,
            createdAt = 2000L,
            updatedAt = 2000L
        )

        sessionDao.insertSession(session1)
        sessionDao.insertSession(session2)
        delay(100)

        // Before message insert, Session 2 is first in list (updatedAt 2000 > 1000)
        val initialSessions = sessionDao.getSessions()
        assertEquals("session-2", initialSessions.first().id)

        // Insert a new message into Session 1 at timestamp 3000L
        val msg = UnifiedMessageEntity(
            id = "msg-new",
            sessionId = "session-1",
            role = "USER",
            content = "New activity in Session 1",
            createdAt = 3000L
        )
        sessionDao.insertOrUpdateMessage(msg)
        delay(100)

        // Verify session-1 updatedAt was updated
        val updatedSessions = sessionDao.getSessions()
        val updatedSession1 = updatedSessions.find { it.id == "session-1" }
        assertTrue("Session 1 updatedAt must be bumped after message insertion", (updatedSession1?.updatedAt ?: 0L) >= 3000L)

        // Verify session list is reordered with session-1 at the top
        assertEquals("session-1 must be at the top of the session list after new message", "session-1", updatedSessions.first().id)

        scope.cancel()
    }
}
