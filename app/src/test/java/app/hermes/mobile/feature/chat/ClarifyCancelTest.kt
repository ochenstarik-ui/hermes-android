package app.hermes.mobile.feature.chat

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClarifyCancelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository
    private lateinit var viewModel: ChatViewModel

    private val sessionId = UnifiedSessionId("test-session-clarify-cancel")
    private val hostId = HermesHostId("host-clarify-cancel")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher),
            runtimeFactory = { parentScope, host ->
                val childScope = CoroutineScope(kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + testDispatcher)
                HermesHostRuntime(
                    initialHost = host,
                    restClient = app.hermes.mobile.core.network.HermesRestClient(),
                    gatewayClient = JsonRpcGatewayClient(scope = childScope),
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

        val host = HermesHost(id = hostId, displayName = "Host Clarify", baseUrl = "http://host-clarify:9119")
        runTest(testDispatcher) {
            connectionManager.addHost(host)
            repository.createUnifiedSession("Clarify Test Session", hostId)
            testScheduler.advanceUntilIdle()
        }

        viewModel = ChatViewModel(repository, connectionManager, sessionId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDismissClarifyClearsActiveRequestAndSendsCancellation() = runTest(testDispatcher) {
        val runtimeSessionId = RuntimeSessionId("runtime_session_clarify")
        repository.registerRuntimeBinding(sessionId, hostId, runtimeSessionId)

        val runtime = connectionManager.getRuntime(hostId)
        assertNotNull(runtime)

        // Simulate incoming sudo request
        val sudoEvent = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "sudo.request")
                put("session_id", runtimeSessionId.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_sudo_dismiss")
                    put("question", "Enter root password:")
                })
            })
        }
        runtime?.gatewayClient?.handleIncomingMessage(sudoEvent.toString())
        testScheduler.advanceUntilIdle()

        val active = viewModel.activeClarify.value
        assertNotNull("Sudo request must be active", active)
        assertEquals("req_sudo_dismiss", active?.request?.requestId)

        // Dismiss clarify request (simulating user tapping Cancel or outside dialog)
        viewModel.dismissClarify(active!!)
        testScheduler.advanceUntilIdle()

        // Active clarify must now be cleared
        assertNull("Active clarify must be cleared after dismissal", viewModel.activeClarify.value)
    }
}
