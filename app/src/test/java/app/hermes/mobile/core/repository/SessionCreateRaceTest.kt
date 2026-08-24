package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionCreateRaceTest {

    @Test
    fun testConcurrentSendPromptCreatesExactlyOneNativeSession() = runBlocking {
        val hostId = HermesHostId("race-host-1")
        val host = HermesHost(id = hostId, displayName = "Race Host", baseUrl = "http://race-host:9119")

        val hostDao = FakeHostDao()
        val sessionDao = FakeUnifiedSessionDao()
        val tokenVault = InMemoryTokenVault()
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val createCallCount = AtomicInteger(0)

        val mockGatewayClient = mockk<JsonRpcGatewayClient>(relaxed = true)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { mockGatewayClient.connectionState } returns MutableStateFlow(ConnectionState.Connected)
        every { mockGatewayClient.events } returns MutableSharedFlow()
        coEvery { mockGatewayClient.awaitGatewayReady(any()) } returns Unit
        coEvery { mockGatewayClient.createSession(any(), any()) } coAnswers {
            createCallCount.incrementAndGet()
            delay(100) // simulate network delay to expose race condition
            CreateSessionResult(
                durableId = DurableSessionId("dur_race_1"),
                runtimeId = RuntimeSessionId("rt_race_1")
            )
        }
        coEvery { mockGatewayClient.submitPrompt(any(), any()) } coAnswers {
            PromptSubmitResult(turnId = "turn_1", accepted = true)
        }

        val runtime = HermesHostRuntime(
            initialHost = host,
            gatewayClient = mockGatewayClient,
            tokenVault = tokenVault,
            scope = runtimeScope
        )

        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = repoScope,
            runtimeFactory = { _, _ -> runtime }
        )

        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = repoScope
        )

        connectionManager.addHost(host)
        delay(50)

        val session = repository.createUnifiedSession(title = "Race Test Session", initialHostId = hostId)
        delay(50)

        // Launch 2 concurrent sendPrompt calls for the same session & host
        val deferred1 = async(Dispatchers.Default) {
            repository.sendPrompt(session.id, "Prompt from coroutine 1")
        }
        val deferred2 = async(Dispatchers.Default) {
            repository.sendPrompt(session.id, "Prompt from coroutine 2")
        }

        awaitAll(deferred1, deferred2)

        assertEquals("Exactly one session.create must be invoked for concurrent sendPrompt calls", 1, createCallCount.get())

        repoScope.cancel()
        runtimeScope.cancel()
    }
}
