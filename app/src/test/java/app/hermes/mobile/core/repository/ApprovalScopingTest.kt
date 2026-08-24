package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.feature.chat.ChatViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalScopingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository

    private val host1Id = HermesHostId("host-scoping-1")
    private val host2Id = HermesHostId("host-scoping-2")

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testApprovalsAreScopedToUnifiedSessionAndDoNotLeakToOtherSessions() = runTest(testDispatcher) {
        val host1 = HermesHost(id = host1Id, displayName = "Host One", baseUrl = "http://host1:9119")
        connectionManager.addHost(host1)
        testScheduler.advanceUntilIdle()

        val sessionA = repository.createUnifiedSession("Session A", host1Id)
        val sessionB = repository.createUnifiedSession("Session B", host1Id)
        testScheduler.advanceUntilIdle()

        // Register runtime binding for Session A
        val runtimeSessionIdA = RuntimeSessionId("runtime_session_A_100")
        repository.registerRuntimeBinding(sessionA.id, host1Id, runtimeSessionIdA)

        val runtime1 = connectionManager.getRuntime(host1Id)
        assertNotNull(runtime1)

        // Simulate incoming approval for Session A
        val approvalEvent = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "approval.request")
                put("session_id", runtimeSessionIdA.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_session_A")
                    put("command", "rm -rf /tmp/data")
                    put("description", "Delete temp files")
                })
            })
        }
        runtime1?.gatewayClient?.handleIncomingMessage(approvalEvent.toString())
        testScheduler.advanceUntilIdle()

        val chatVmA = ChatViewModel(repository, connectionManager, sessionA.id)
        val chatVmB = ChatViewModel(repository, connectionManager, sessionB.id)

        // Session A must receive the approval
        assertEquals("Session A must receive its approval", 1, chatVmA.activeApprovals.value.size)
        assertEquals("req_session_A", chatVmA.activeApprovals.value.first().approval.requestId)

        // Session B must NOT see Session A's approval
        assertEquals("Session B must not see approvals belonging to Session A", 0, chatVmB.activeApprovals.value.size)
    }

    @Test
    fun testMultipleClarifyRequestsFromDifferentHostsCoexistInQueueWithoutOverwriting() = runTest(testDispatcher) {
        val host1 = HermesHost(id = host1Id, displayName = "Host One", baseUrl = "http://host1:9119")
        val host2 = HermesHost(id = host2Id, displayName = "Host Two", baseUrl = "http://host2:9119")
        connectionManager.addHost(host1)
        connectionManager.addHost(host2)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession("Multi Host Session", host1Id)
        testScheduler.advanceUntilIdle()

        val runtimeSession1 = RuntimeSessionId("runtime_h1")
        val runtimeSession2 = RuntimeSessionId("runtime_h2")
        repository.registerRuntimeBinding(session.id, host1Id, runtimeSession1)
        repository.registerRuntimeBinding(session.id, host2Id, runtimeSession2)

        val runtime1 = connectionManager.getRuntime(host1Id)
        val runtime2 = connectionManager.getRuntime(host2Id)
        assertNotNull(runtime1)
        assertNotNull(runtime2)

        // Host 1 sends Sudo request
        val sudoEventHost1 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "sudo.request")
                put("session_id", runtimeSession1.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_sudo_h1")
                    put("question", "Enter sudo password for Host 1:")
                })
            })
        }
        runtime1?.gatewayClient?.handleIncomingMessage(sudoEventHost1.toString())
        testScheduler.advanceUntilIdle()

        // Host 2 sends Clarify request
        val clarifyEventHost2 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "clarify.request")
                put("session_id", runtimeSession2.value)
                put("payload", buildJsonObject {
                    put("request_id", "req_clarify_h2")
                    put("question", "Clarify target directory:")
                    put("question_id", "q_dir")
                })
            })
        }
        runtime2?.gatewayClient?.handleIncomingMessage(clarifyEventHost2.toString())
        testScheduler.advanceUntilIdle()

        val chatVm = ChatViewModel(repository, connectionManager, session.id)

        // First clarify request should be active
        val firstActive = chatVm.activeClarify.value
        assertNotNull("First clarify request must be active", firstActive)
        assertEquals("req_sudo_h1", firstActive?.request?.requestId)

        // Dismiss/resolve the first clarify request
        chatVm.dismissClarify(firstActive!!)
        testScheduler.advanceUntilIdle()

        // The second clarify request must NOT have been overwritten/lost; it must now be active!
        val secondActive = chatVm.activeClarify.value
        assertNotNull("Second clarify request from Host 2 must become active after first is resolved", secondActive)
        assertEquals("req_clarify_h2", secondActive?.request?.requestId)
        assertEquals(host2Id, secondActive?.hostId)
    }
}
