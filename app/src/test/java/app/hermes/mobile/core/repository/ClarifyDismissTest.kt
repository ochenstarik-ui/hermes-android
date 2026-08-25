package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.RuntimeSessionId
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClarifyDismissTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var mockGatewayClient: JsonRpcGatewayClient
    private lateinit var connectionStateFlow: MutableStateFlow<ConnectionState>
    private lateinit var gatewayEventsFlow: MutableSharedFlow<app.hermes.mobile.core.model.GatewayEvent>
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository

    private val hostId = HermesHostId("test-host-dismiss")
    private val sessionId = UnifiedSessionId("test-session-dismiss")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()
        mockGatewayClient = mockk(relaxed = true)

        connectionStateFlow = MutableStateFlow(ConnectionState.Connected)
        gatewayEventsFlow = MutableSharedFlow()

        every { mockGatewayClient.connectionState } returns connectionStateFlow
        every { mockGatewayClient.events } returns gatewayEventsFlow
        coEvery { mockGatewayClient.respondSudo(any(), any()) } returns true
        coEvery { mockGatewayClient.respondSecret(any(), any()) } returns true
        coEvery { mockGatewayClient.respondClarify(any(), any(), any()) } returns true

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher),
            runtimeFactory = { parentScope, host ->
                val childScope = CoroutineScope(kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + testDispatcher)
                HermesHostRuntime(
                    initialHost = host,
                    restClient = HermesRestClient(),
                    gatewayClient = mockGatewayClient,
                    tokenVault = tokenVault,
                    scope = childScope
                )
            }
        )

        repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = CoroutineScope(testDispatcher)
        )

        val host = HermesHost(id = hostId, displayName = "Dismiss Host", baseUrl = "http://test-dismiss:9119")
        runTest(testDispatcher) {
            connectionManager.addHost(host)
            repository.createUnifiedSession("Dismiss Test Session", hostId)
            testScheduler.advanceUntilIdle()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDismissSudoDoesNotSendEmptyPasswordToGateway() = runTest(testDispatcher) {
        val runtimeSessionId = RuntimeSessionId("rt_dismiss_sudo")
        repository.registerRuntimeBinding(sessionId, hostId, runtimeSessionId)

        val sudoEvent = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "sudo.request")
                put("session_id", runtimeSessionId.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_sudo_dismiss_123")
                    put("question", "Enter sudo password:")
                })
            })
        }
        val parsedEvent = app.hermes.mobile.core.model.GatewayEvent.parse(sudoEvent)
        assertNotNull(parsedEvent)
        gatewayEventsFlow.emit(parsedEvent!!)
        testScheduler.advanceUntilIdle()

        assertNotNull("Active clarify must be present before dismissal", repository.activeClarify.value)

        // Dismiss clarify request
        repository.dismissClarify(hostId, "req_sudo_dismiss_123", ClarifyType.SUDO)
        testScheduler.advanceUntilIdle()

        // Sudo respond MUST NOT be invoked with empty string or any fake value
        coVerify(exactly = 0) {
            mockGatewayClient.respondSudo(any(), any())
        }

        // Active clarify must be cleared locally
        assertNull("Active clarify must be null after dismissal", repository.activeClarify.value)
    }

    @Test
    fun testDismissSecretDoesNotSendEmptySecretToGateway() = runTest(testDispatcher) {
        val runtimeSessionId = RuntimeSessionId("rt_dismiss_secret")
        repository.registerRuntimeBinding(sessionId, hostId, runtimeSessionId)

        val secretEvent = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "secret.request")
                put("session_id", runtimeSessionId.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_secret_dismiss_456")
                    put("question", "Enter API secret token:")
                })
            })
        }
        val parsedEvent = app.hermes.mobile.core.model.GatewayEvent.parse(secretEvent)
        assertNotNull(parsedEvent)
        gatewayEventsFlow.emit(parsedEvent!!)
        testScheduler.advanceUntilIdle()

        assertNotNull("Active clarify must be present before dismissal", repository.activeClarify.value)

        // Dismiss clarify request
        repository.dismissClarify(hostId, "req_secret_dismiss_456", ClarifyType.SECRET)
        testScheduler.advanceUntilIdle()

        // Secret respond MUST NOT be invoked with empty string
        coVerify(exactly = 0) {
            mockGatewayClient.respondSecret(any(), any())
        }

        // Active clarify must be cleared locally
        assertNull("Active clarify must be null after dismissal", repository.activeClarify.value)
    }

    @Test
    fun testDismissClarifyQuestionDoesNotSendEmptyResponse() = runTest(testDispatcher) {
        val runtimeSessionId = RuntimeSessionId("rt_dismiss_clarify")
        repository.registerRuntimeBinding(sessionId, hostId, runtimeSessionId)

        val clarifyEvent = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "clarify.request")
                put("session_id", runtimeSessionId.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_clarify_dismiss_789")
                    put("question_id", "q_42")
                    put("question", "Which file do you mean?")
                })
            })
        }
        val parsedEvent = app.hermes.mobile.core.model.GatewayEvent.parse(clarifyEvent)
        assertNotNull(parsedEvent)
        gatewayEventsFlow.emit(parsedEvent!!)
        testScheduler.advanceUntilIdle()

        assertNotNull("Active clarify must be present before dismissal", repository.activeClarify.value)

        // Dismiss clarify request
        repository.dismissClarify(hostId, "req_clarify_dismiss_789", ClarifyType.CLARIFY, "q_42")
        testScheduler.advanceUntilIdle()

        // Clarify respond MUST NOT be invoked with empty string
        coVerify(exactly = 0) {
            mockGatewayClient.respondClarify(any(), any(), any())
        }

        // Active clarify must be cleared locally
        assertNull("Active clarify must be null after dismissal", repository.activeClarify.value)
    }
}
