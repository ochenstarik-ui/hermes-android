package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.HermesConnection
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.repository.HermesGatewayRepository
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private lateinit var gatewayClient: JsonRpcGatewayClient
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var repository: HermesGatewayRepository

    private var serverWs: WebSocket? = null
    private val wsConnectedLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        restClient = HermesRestClient()
        gatewayClient = JsonRpcGatewayClient()
        tokenVault = InMemoryTokenVault()
        repository = HermesGatewayRepository(restClient, gatewayClient, tokenVault)
    }

    @After
    fun tearDown() {
        serverWs?.close(1000, "done")
        repository.disconnect()
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
                                webSocket.send("""{"event":"gateway.ready","data":{"version":"1.0.0","session_count":1}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.list")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":[{"id":"durable_100","title":"Existing Session","preview":"Hello!","started_at":1700000000,"message_count":2,"source":"android"}]}""")
                                } else if (text.contains("session.create")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"stored_session_id":"durable_101","session_id":"runtime_202"}}""")
                                } else if (text.contains("prompt.submit")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a3","result":{"turn_id":"turn_001"}}""")
                                    // Emit streaming events
                                    webSocket.send("""{"event":"message.start","data":{"message_id":"msg_resp_1","role":"assistant"}}""")
                                    webSocket.send("""{"event":"message.delta","data":{"message_id":"msg_resp_1","delta":"Sure, I can "}}""")
                                    webSocket.send("""{"event":"message.delta","data":{"message_id":"msg_resp_1","delta":"run that tool."}}""")
                                    webSocket.send("""{"event":"tool.start","data":{"tool_id":"t_exec","name":"run_command"}}""")
                                    webSocket.send("""{"event":"tool.progress","data":{"tool_id":"t_exec","progress":"Executing ls..."}}""")
                                    webSocket.send("""{"event":"tool.complete","data":{"tool_id":"t_exec","result":"file1.txt\nfile2.txt","is_error":false}}""")
                                    webSocket.send("""{"event":"approval.request","data":{"request_id":"app_req_1","command":"git status","description":"Run git status","choices":["once","deny"]}}""")
                                } else if (text.contains("approval.respond")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a4","result":{"accepted":true}}""")
                                    webSocket.send("""{"event":"message.complete","data":{"message_id":"msg_resp_1","content":"Sure, I can run that tool. Done!"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val conn = HermesConnection(
            id = "test_conn_1",
            name = "Test Server",
            baseUrl = serverUrl,
            allowCleartext = true
        )

        // 1. Status check
        val statusRes = repository.checkStatus(conn)
        assertTrue(statusRes.isSuccess)
        val status = statusRes.getOrThrow()
        assertTrue(status.authRequired)

        // 2. Token exchange fixture
        val exchangeRes = restClient.exchangeNativeToken(
            baseUrl = conn.baseUrl,
            code = "auth_code_123",
            codeVerifier = "code_verifier_123",
            allowCleartext = true
        )
        assertTrue(exchangeRes.isSuccess)
        val tokens = exchangeRes.getOrThrow()
        tokenVault.saveTokens(conn.id, tokens)

        // 3 & 4. Connect repository
        val connectRes = repository.connect(conn)
        assertTrue(connectRes.isSuccess)

        assertTrue(wsConnectedLatch.await(5, TimeUnit.SECONDS))
        val state = withTimeout(5000) {
            repository.connectionState.first { it is ConnectionState.Connected }
        }
        assertTrue(state is ConnectionState.Connected)

        // 6. List sessions
        val sessionList = repository.listSessions()
        assertEquals(1, sessionList.size)
        assertEquals(DurableSessionId("durable_100"), sessionList[0].id)

        // 7. Create new session
        val createResult = repository.startNewSession()
        assertEquals(DurableSessionId("durable_101"), createResult.durableId)
        assertEquals(repository.activeDurableId.value, DurableSessionId("durable_101"))

        // 8. Submit user prompt
        val submitRes = repository.sendUserPrompt("Run git status")
        assertTrue(submitRes.accepted)

        // Wait for streaming delta and approval request
        withTimeout(5000) {
            while (repository.messages.value.none { it.role == MessageRole.ASSISTANT && it.content.isNotEmpty() }) {
                kotlinx.coroutines.delay(50)
            }
        }

        val assistantMsg = repository.messages.value.find { it.role == MessageRole.ASSISTANT }
        assertNotNull(assistantMsg)
        assertTrue(assistantMsg!!.content.contains("Sure, I can"))

        withTimeout(5000) {
            while (repository.activeApprovals.value.isEmpty()) {
                kotlinx.coroutines.delay(50)
            }
        }
        assertEquals(1, repository.activeApprovals.value.size)
        val approval = repository.activeApprovals.value[0]
        assertEquals("app_req_1", approval.requestId)
        assertEquals("git status", approval.command)

        // 10. Respond to approval
        val approvalRes = repository.respondApproval("app_req_1", "once", false)
        assertTrue(approvalRes)
        assertTrue(repository.activeApprovals.value.isEmpty())

        // 11. Wait for completion
        withTimeout(5000) {
            while (repository.isExecuting.value) {
                kotlinx.coroutines.delay(50)
            }
        }
        assertFalse(repository.isExecuting.value)
        val completedMsg = repository.messages.value.find { it.role == MessageRole.ASSISTANT }
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
                                webSocket.send("""{"event":"gateway.ready","data":{"version":"1.0.0","session_count":0}}""")
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val conn = HermesConnection(
            id = "refresh_conn_1",
            name = "Refresh Server",
            baseUrl = serverUrl,
            allowCleartext = true
        )

        // Expired token (expiresAt = 1000, current time is > 1000)
        tokenVault.saveTokens(
            conn.id,
            app.hermes.mobile.core.model.NativeAuthTokens(
                accessToken = "expired_token",
                refreshToken = "rt_initial",
                expiresAt = 1000L
            )
        )

        val connectRes = repository.connect(conn)
        assertTrue(connectRes.isSuccess)
        assertTrue(refreshed)

        val newTokens = tokenVault.getTokens(conn.id)
        assertNotNull(newTokens)
        assertEquals("refreshed_access_token", newTokens?.accessToken)
    }
}
