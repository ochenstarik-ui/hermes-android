package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalRoutingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var sessionRepo: UnifiedSessionRepository
    private lateinit var mockServer: MockWebServer

    private val host1Id = HermesHostId("server-prod")
    private val host2Id = HermesHostId("server-dev")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()
        mockServer = MockWebServer()
        mockServer.start()

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher)
        )

        sessionRepo = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        try {
            mockServer.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun testApprovalFailureRemainsVisibleAndNotResolved() = runTest(testDispatcher) {
        val host1 = HermesHost(id = host1Id, displayName = "Prod Server", baseUrl = "http://prod:9119")
        connectionManager.addHost(host1)
        testScheduler.advanceUntilIdle()

        val runtime1 = connectionManager.getRuntime(host1Id)
        assertNotNull(runtime1)

        // Simulate incoming approval request with specific runtime session ID
        val prodEventJson = buildJsonObject {
            put("method", "event")
            put("params", buildJsonObject {
                put("event", "approval.request")
                put("request_id", "req_prod_1")
                put("session_key", "runtime_session_prod_99")
                put("command", "systemctl restart nginx")
                put("description", "Restart web server")
            })
        }
        runtime1?.gatewayClient?.handleIncomingMessage(prodEventJson.toString())
        testScheduler.advanceUntilIdle()

        val approvals = sessionRepo.activeApprovals.value
        assertEquals(1, approvals.size)
        val approval = approvals.first()
        assertEquals(RuntimeSessionId("runtime_session_prod_99"), approval.runtimeSessionId)
        assertEquals("req_prod_1", approval.approval.requestId)

        // Attempting to respond when WebSocket is disconnected MUST return false and KEEP approval
        val result = sessionRepo.respondApproval(
            hostId = host1Id,
            runtimeSessionId = RuntimeSessionId("runtime_session_prod_99"),
            requestId = "req_prod_1",
            choice = "once",
            all = false
        )
        testScheduler.advanceUntilIdle()

        assertFalse("Expected false when RPC fails due to disconnected socket", result)
        assertEquals("Approval must not be removed on failure", 1, sessionRepo.activeApprovals.value.size)
    }

    @Test
    fun testApprovalSuccessRemovesCardAndSendsCorrectRpc() = runBlocking(Dispatchers.Default) {
        val receivedMessages = mutableListOf<String>()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"event":"gateway.ready","data":{"version":"1.0.0"}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                receivedMessages.add(text)
                                if (text.contains("approval.respond")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"status":"ok"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val wsUrl = mockServer.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        val testConnectionManager = HermesConnectionManager(
            hostDao = testHostDao,
            tokenVault = testTokenVault,
            scope = CoroutineScope(Dispatchers.Default)
        )
        val testRepo = UnifiedSessionRepository(
            connectionManager = testConnectionManager,
            sessionDao = testSessionDao,
            scope = CoroutineScope(Dispatchers.Default)
        )

        val host1 = HermesHost(id = host1Id, displayName = "Prod Server", baseUrl = wsUrl, allowCleartext = true)
        testConnectionManager.addHost(host1)

        val runtime1 = testConnectionManager.getRuntime(host1Id)
        assertNotNull(runtime1)

        runtime1!!.connect()
        runtime1.gatewayClient.awaitGatewayReady(5000)

        // Incoming approval
        val prodEventJson = buildJsonObject {
            put("method", "event")
            put("params", buildJsonObject {
                put("event", "approval.request")
                put("request_id", "req_prod_1")
                put("session_id", "runtime_session_prod_99")
                put("command", "systemctl restart nginx")
            })
        }
        runtime1.gatewayClient.handleIncomingMessage(prodEventJson.toString())

        var waited = 0
        while (testRepo.activeApprovals.value.isEmpty() && waited < 50) {
            kotlinx.coroutines.delay(50)
            waited++
        }

        assertEquals(1, testRepo.activeApprovals.value.size)

        // Respond to approval
        val result = testRepo.respondApproval(
            hostId = host1Id,
            runtimeSessionId = RuntimeSessionId("runtime_session_prod_99"),
            requestId = "req_prod_1",
            choice = "once",
            all = false
        )

        assertTrue("Expected true on successful RPC response", result)
        assertEquals("Approval should be removed after success", 0, testRepo.activeApprovals.value.size)

        // Verify sent RPC wire payload
        assertTrue(receivedMessages.any {
            it.contains("\"session_id\":\"runtime_session_prod_99\"") &&
            it.contains("\"request_id\":\"req_prod_1\"") &&
            it.contains("\"choice\":\"once\"")
        })

        runtime1.disconnect()
    }
}
