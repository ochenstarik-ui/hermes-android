package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.HostBindingEntity
import app.hermes.mobile.core.storage.UnifiedMessageEntity
import app.hermes.mobile.core.storage.UnifiedSessionDao
import app.hermes.mobile.core.storage.UnifiedSessionEntity
import app.hermes.mobile.core.storage.UnifiedSessionWithDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that loading the list of 100 sessions does not perform N+1 database queries.
 * On base SHA, collecting sessions flow causes 100+ queries (1 getSessionsFlow + 100 getSessionWithDetails).
 */
class SessionListQueryCountTest {

    private class TrackingUnifiedSessionDao(
        private val delegate: FakeUnifiedSessionDao
    ) : UnifiedSessionDao by delegate {
        val queryCount = AtomicInteger(0)
        val detailsQueryCount = AtomicInteger(0)

        override fun getSessionsFlow(): Flow<List<UnifiedSessionEntity>> {
            queryCount.incrementAndGet()
            return delegate.getSessionsFlow()
        }

        override suspend fun getSessions(): List<UnifiedSessionEntity> {
            queryCount.incrementAndGet()
            return delegate.getSessions()
        }

        override suspend fun getSession(sessionId: String): UnifiedSessionEntity? {
            queryCount.incrementAndGet()
            return delegate.getSession(sessionId)
        }

        override suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails? {
            queryCount.incrementAndGet()
            detailsQueryCount.incrementAndGet()
            return delegate.getSessionWithDetails(sessionId)
        }

        override fun getUnifiedSessionsSummaryFlow(): Flow<List<app.hermes.mobile.core.storage.UnifiedSessionSummaryProjection>> {
            queryCount.incrementAndGet()
            return delegate.getUnifiedSessionsSummaryFlow()
        }

        override suspend fun getUnifiedSessionsSummary(): List<app.hermes.mobile.core.storage.UnifiedSessionSummaryProjection> {
            queryCount.incrementAndGet()
            return delegate.getUnifiedSessionsSummary()
        }

        override suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity> {
            queryCount.incrementAndGet()
            return delegate.getMessagesForSession(sessionId)
        }

        override suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity> {
            queryCount.incrementAndGet()
            return delegate.getBindingsForSession(sessionId)
        }
    }

    @Test
    fun test100SessionsListQueryCountIsFixedAndNotNPlusOne() = runBlocking {
        val hostDao = FakeHostDao()
        val rawSessionDao = FakeUnifiedSessionDao()
        val trackingDao = TrackingUnifiedSessionDao(rawSessionDao)
        val tokenVault = InMemoryTokenVault()
        val scope = CoroutineScope(Dispatchers.Default)

        val hostId = HermesHostId("host-perf-1")
        val host = HermesHost(id = hostId, displayName = "Host Perf", baseUrl = "http://host-perf:9119")
        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = scope
        )
        connectionManager.addHost(host)

        val sessionCount = 100
        val messagesPerSession = 20

        // Populate 100 sessions with 20 messages each
        for (i in 1..sessionCount) {
            val sid = "session-$i"
            rawSessionDao.insertSession(
                UnifiedSessionEntity(
                    id = sid,
                    title = "Session $i",
                    activeHostId = hostId.value,
                    createdAt = 1000L + i,
                    updatedAt = 1000L + i
                )
            )
            val msgs = (1..messagesPerSession).map { mIdx ->
                UnifiedMessageEntity(
                    id = "msg-$i-$mIdx",
                    sessionId = sid,
                    role = if (mIdx % 2 == 0) "ASSISTANT" else "USER",
                    content = "Content $i - $mIdx",
                    hostId = hostId.value,
                    createdAt = 1000L + i * 100 + mIdx
                )
            }
            rawSessionDao.insertMessages(msgs)
        }

        // Reset tracking counters before initializing repository & collecting sessions
        trackingDao.queryCount.set(0)
        trackingDao.detailsQueryCount.set(0)

        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = trackingDao,
            scope = scope
        )

        // Read the sessions flow
        val sessionsList = repository.sessions.first { it.size == sessionCount }
        assertEquals(sessionCount, sessionsList.size)

        val totalQueries = trackingDao.queryCount.get()
        val detailsQueries = trackingDao.detailsQueryCount.get()

        println("BASELINE MEASUREMENT [100 Sessions List]: totalQueries=$totalQueries, detailsQueries=$detailsQueries")

        // In a lightweight projection, detailsQueries must be 0 and totalQueries must be <= 2 (fixed count, O(1))
        assertEquals("Should not invoke getSessionWithDetails in N+1 loop", 0, detailsQueries)
        assertTrue("Total queries ($totalQueries) must be <= 2, not O(N)", totalQueries <= 2)
    }
}
