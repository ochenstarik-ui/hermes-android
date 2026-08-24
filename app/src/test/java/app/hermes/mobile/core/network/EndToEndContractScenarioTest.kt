package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EndToEndContractScenarioTest {

    private lateinit var server: MockWebServer
    private lateinit var restClient: HermesRestClient
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var testScope: CoroutineScope
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository

    private var serverWs: WebSocket? = null
    private val wsConnectedLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        restClient = HermesRestClient()
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            restClient = restClient,
            scope = testScope
        )
        repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        serverWs?.close(1000, "done")
        testScope.cancel()
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun testFullContractScenarioLifecycle() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"status":"ok","auth_required":true,"auth_providers":["github"],"version":"1.0.0"}"""
                        )
                    }
                    path == "/auth/native/token" -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"access_token":"jwt_access_123","refresh_token":"rt_456","token_type":"Bearer","expires_at":2000000000,"user_id":"hermes_user"}"""
                        )
                    }
                    path == "/api/auth/ws-ticket" -> {
                        val authHeader = request.getHeader("Authorization")
                        if (authHeader == "Bearer jwt_access_123") {
                            MockResponse().setResponseCode(200).setBody(
                                """{"ticket":"ticket_xyz_789","ttl_seconds":30}"""
                            )
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}""")
                        }
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverWs = webSocket
                                wsConnectedLatch.countDown()
                                // Send gateway.ready
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":1}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val reqId = try {
                                    val root = Json.decodeFromString<JsonObject>(text)
                                    root["id"]?.jsonPrimitive?.content ?: "1"
                                } catch (_: Exception) {
                                    "1"
                                }

                                if (text.contains("session.create")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"$reqId","result":{"stored_session_id":"durable_101","session_id":"runtime_202"}}""")
                                } else if (text.contains("prompt.submit")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"$reqId","result":{"turn_id":"turn_001"}}""")
                                    // Emit streaming events
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"runtime_202","payload":{"message_id":"msg_resp_1","role":"assistant"}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime_202","payload":{"message_id":"msg_resp_1","delta":"Sure, I can "}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime_202","payload":{"message_id":"msg_resp_1","delta":"run that tool."}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"runtime_202","payload":{"tool_id":"t_exec","name":"run_command"}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.progress","session_id":"runtime_202","payload":{"tool_id":"t_exec","progress":"Executing ls..."}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"runtime_202","payload":{"tool_id":"t_exec","result":"file1.txt\nfile2.txt","is_error":false}}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"runtime_202","payload":{"request_id":"app_req_1","command":"git status","description":"Run git status","choices":["once","deny"]}}}""")
                                } else if (text.contains("approval.respond")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"$reqId","result":{"accepted":true}}""")
                                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime_202","payload":{"message_id":"msg_resp_1","content":"Sure, I can run that tool. Done!"}}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val hostId = HermesHostId("test_host_1")
        val host = HermesHost(
            id = hostId,
            displayName = "Test Server",
            baseUrl = serverUrl,
            allowCleartext = true
        )

        // 1. Status check
        val statusRes = restClient.getStatus(host.baseUrl, allowCleartext = host.allowCleartext)
        assertTrue(statusRes.isSuccess)
        val status = statusRes.getOrThrow()
        assertTrue(status.authRequired)

        // 2. Token exchange fixture
        val exchangeRes = restClient.exchangeNativeToken(
            baseUrl = host.baseUrl,
            code = "auth_code_123",
            codeVerifier = "code_verifier_123",
            allowCleartext = host.allowCleartext
        )
        assertTrue(exchangeRes.isSuccess)
        val tokens = exchangeRes.getOrThrow()
        tokenVault.saveTokens(host.id.value, tokens)

        // 3. Add host & connect
        connectionManager.addHost(host)
        val connectRes = connectionManager.connectHost(host.id)
        assertTrue(connectRes.isSuccess)

        assertTrue(wsConnectedLatch.await(5, TimeUnit.SECONDS))
        val hostOnline = withTimeout(5000) {
            hostDao.getHostsFlow().first { hosts ->
                hosts.any { it.id == host.id.value && it.lastKnownStatus == HostStatus.ONLINE.name }
            }
        }
        assertNotNull(hostOnline)

        // 4. Create new Unified Session
        val session = repository.createUnifiedSession(title = "Existing Session", initialHostId = host.id)
        assertEquals(host.id, session.activeHostId)

        // 5. Submit user prompt
        val turnId = repository.sendPrompt(session.id, "Run git status")
        assertEquals("turn_001", turnId)

        // 6. Wait for streaming delta
        val assistantMsg = withTimeout(5000) {
            repository.getSessionMessages(session.id).first { msgs ->
                msgs.any { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }
            }.find { it.role == MessageRole.ASSISTANT }
        }
        assertNotNull(assistantMsg)
        assertTrue(assistantMsg!!.content.contains("Sure, I can"))

        // 7. Wait for approval request
        val approvals = withTimeout(5000) {
            repository.getActiveApprovals(session.id).first { it.isNotEmpty() }
        }
        assertEquals(1, approvals.size)
        val approval = approvals[0]
        assertEquals("app_req_1", approval.approval.requestId)
        assertEquals("git status", approval.approval.command)

        // 8. Respond to approval
        val approvalRes = repository.respondApproval(
            hostId = approval.hostId,
            runtimeSessionId = approval.runtimeSessionId,
            requestId = approval.approval.requestId,
            choice = "once",
            all = false
        )
        assertTrue(approvalRes)
        withTimeout(5000) {
            repository.getActiveApprovals(session.id).first { it.isEmpty() }
        }
        assertEquals(0, repository.getActiveApprovals(session.id).value.size)

        // 9. Wait for completion
        withTimeout(5000) {
            repository.getSessionExecuting(session.id).first { !it }
        }
        assertFalse(repository.getSessionExecuting(session.id).value)

        val completedMsg = withTimeout(5000) {
            repository.getSessionMessages(session.id).first { msgs ->
                msgs.any { it.role == MessageRole.ASSISTANT && !it.isStreaming }
            }.find { it.role == MessageRole.ASSISTANT }
        }
        assertNotNull(completedMsg)
        assertEquals("Sure, I can run that tool. Done!", completedMsg!!.content)
        assertFalse(completedMsg.isStreaming)
    }

    @Test
    fun testTokenRefreshOnExpiringToken() = runBlocking {
        val serverUrl = server.url("").toString().removeSuffix("/")
        var refreshed = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"status":"ok","auth_required":true,"auth_providers":["github"],"version":"1.0.0"}"""
                        )
                    }
                    path == "/auth/native/refresh" -> {
                        refreshed = true
                        MockResponse().setResponseCode(200).setBody(
                            """{"access_token":"refreshed_access_token","refresh_token":"rt_789","token_type":"Bearer","expires_at":2500000000,"user_id":"hermes_user"}"""
                        )
                    }
                    path == "/api/auth/ws-ticket" -> {
                        val authHeader = request.getHeader("Authorization")
                        if (authHeader == "Bearer refreshed_access_token") {
                            MockResponse().setResponseCode(200).setBody(
                                """{"ticket":"fresh_ticket_999","ttl_seconds":30}"""
                            )
                        } else {
                            MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized"}""")
                        }
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":0}}}""")
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val hostId = HermesHostId("refresh_host_1")
        val host = HermesHost(
            id = hostId,
            displayName = "Refresh Server",
            baseUrl = serverUrl,
            allowCleartext = true
        )

        // Expired token (expiresAt = 1000, current time is > 1000)
        tokenVault.saveTokens(
            host.id.value,
            NativeAuthTokens(
                accessToken = "expired_token",
                refreshToken = "rt_initial",
                expiresAt = 1000L
            )
        )

        connectionManager.addHost(host)
        val connectRes = connectionManager.connectHost(host.id)
        assertTrue(connectRes.isSuccess)

        val onlineHost = withTimeout(5000) {
            hostDao.getHostsFlow().first { hosts ->
                hosts.any { it.id == host.id.value && it.lastKnownStatus == HostStatus.ONLINE.name }
            }
        }
        assertNotNull(onlineHost)
        assertTrue(refreshed)

        val newTokens = tokenVault.getTokens(host.id.value)
        assertNotNull(newTokens)
        assertEquals("refreshed_access_token", newTokens?.accessToken)
    }
}
