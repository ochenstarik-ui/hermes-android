package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.*
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MultiHostConcurrencyExecutionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var sessionRepo: UnifiedSessionRepository

    private val host1Id = HermesHostId("host-windows")
    private val host2Id = HermesHostId("host-linux")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()

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
    }

    @Test
    fun testTwoHermesSimultaneousStreamToTwoDifferentSessions() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Windows PC", baseUrl = "http://pc:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://linux:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session1 = sessionRepo.createUnifiedSession(title = "Windows Session", initialHostId = host1Id)
        val session2 = sessionRepo.createUnifiedSession(title = "Linux Session", initialHostId = host2Id)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        val runtimeB = connectionManager.getRuntime(host2Id)
        assertNotNull(runtimeA)
        assertNotNull(runtimeB)

        // Register runtime IDs
        sessionRepo.registerRuntimeBinding(session1.id, host1Id, RuntimeSessionId("rt_win_1"))
        sessionRepo.registerRuntimeBinding(session2.id, host2Id, RuntimeSessionId("rt_lin_1"))

        // Register bindings in DB
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session1.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_win_1",
                runtimeSessionId = "rt_win_1",
                state = BindingState.RUNNING.name
            )
        )
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session2.id.value,
                hostId = host2Id.value,
                durableSessionId = "dur_lin_1",
                runtimeSessionId = "rt_lin_1",
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        // Stream from Host A into Session 1 using upstream Hermes envelope
        val eventA1 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", "rt_win_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_a_1")
                    put("role", "assistant")
                })
            })
        }
        val eventA2 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "rt_win_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_a_1")
                    put("delta", "Windows output chunk")
                })
            })
        }

        // Stream from Host B into Session 2 concurrently using upstream Hermes envelope
        val eventB1 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", "rt_lin_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_b_1")
                    put("role", "assistant")
                })
            })
        }
        val eventB2 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "rt_lin_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_b_1")
                    put("delta", "Linux output chunk")
                })
            })
        }

        runtimeA!!.gatewayClient.handleIncomingMessage(eventA1.toString())
        runtimeB!!.gatewayClient.handleIncomingMessage(eventB1.toString())
        testScheduler.advanceUntilIdle()

        runtimeA.gatewayClient.handleIncomingMessage(eventA2.toString())
        runtimeB.gatewayClient.handleIncomingMessage(eventB2.toString())
        testScheduler.advanceUntilIdle()

        val messages1 = sessionRepo.getSessionMessages(session1.id).value
        val messages2 = sessionRepo.getSessionMessages(session2.id).value

        // Assert Session 1 only has Windows messages
        assertTrue(messages1.any { it.id == "msg_a_1" })
        assertFalse(messages1.any { it.id == "msg_b_1" })
        assertEquals("Windows output chunk", messages1.find { it.id == "msg_a_1" }?.content)
        assertEquals(host1Id, messages1.find { it.id == "msg_a_1" }?.hostId)

        // Assert Session 2 only has Linux messages
        assertTrue(messages2.any { it.id == "msg_b_1" })
        assertFalse(messages2.any { it.id == "msg_a_1" })
        assertEquals("Linux output chunk", messages2.find { it.id == "msg_b_1" }?.content)
        assertEquals(host2Id, messages2.find { it.id == "msg_b_1" }?.hostId)
    }

    @Test
    fun testTwoHermesSimultaneousStreamToOneUnifiedSession() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Windows PC", baseUrl = "http://pc:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://linux:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session = sessionRepo.createUnifiedSession(title = "Dual Host Project", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        val runtimeB = connectionManager.getRuntime(host2Id)

        sessionRepo.registerRuntimeBinding(session.id, host1Id, RuntimeSessionId("rt_win_dual"))
        sessionRepo.registerRuntimeBinding(session.id, host2Id, RuntimeSessionId("rt_lin_dual"))

        // Attach both hosts to this one unified session
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_win_dual",
                runtimeSessionId = "rt_win_dual",
                state = BindingState.RUNNING.name
            )
        )
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host2Id.value,
                durableSessionId = "dur_lin_dual",
                runtimeSessionId = "rt_lin_dual",
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        // Host A streams message
        runtimeA!!.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.start")
                    put("session_id", "rt_win_dual")
                    put("payload", buildJsonObject {
                        put("message_id", "msg_win")
                        put("role", "assistant")
                    })
                })
            }.toString()
        )
        testScheduler.advanceUntilIdle()

        runtimeA.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.delta")
                    put("session_id", "rt_win_dual")
                    put("payload", buildJsonObject {
                        put("message_id", "msg_win")
                        put("delta", "Windows result")
                    })
                })
            }.toString()
        )
        testScheduler.advanceUntilIdle()

        // Host B concurrently streams tool
        runtimeB!!.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "tool.start")
                    put("session_id", "rt_lin_dual")
                    put("payload", buildJsonObject {
                        put("tool_id", "tool_lin")
                        put("name", "bash_exec")
                    })
                })
            }.toString()
        )
        testScheduler.advanceUntilIdle()

        runtimeB.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "tool.complete")
                    put("session_id", "rt_lin_dual")
                    put("payload", buildJsonObject {
                        put("tool_id", "tool_lin")
                        put("result", "Linux command completed")
                    })
                })
            }.toString()
        )
        testScheduler.advanceUntilIdle()

        val messages = sessionRepo.getSessionMessages(session.id).value
        val winMsg = messages.find { it.id == "msg_win" }
        assertNotNull(winMsg)
        assertEquals("Windows result", winMsg?.content)
        assertEquals(host1Id, winMsg?.hostId)

        val linToolMsg = messages.find { it.tools.any { t -> t.id == "tool_lin" } }
        assertNotNull(linToolMsg)
        assertEquals(host2Id, linToolMsg?.hostId)
        assertEquals("completed", linToolMsg?.tools?.find { it.id == "tool_lin" }?.status)
    }

    @Test
    fun testIdenticalMessageIdAndRequestIdOnTwoHostsDoNotConflict() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Windows PC", baseUrl = "http://pc:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://linux:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session = sessionRepo.createUnifiedSession(title = "Conflict Resistance", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        val runtimeB = connectionManager.getRuntime(host2Id)

        // Both hosts have same requestId "req_shared_1"
        val approvalEventA = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "approval.request")
                put("session_id", "rt_win_shared")
                put("payload", buildJsonObject {
                    put("request_id", "req_shared_1")
                    put("command", "powershell.exe -Command Get-Process")
                })
            })
        }
        val approvalEventB = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "approval.request")
                put("session_id", "rt_lin_shared")
                put("payload", buildJsonObject {
                    put("request_id", "req_shared_1")
                    put("command", "ps aux")
                })
            })
        }

        runtimeA!!.gatewayClient.handleIncomingMessage(approvalEventA.toString())
        runtimeB!!.gatewayClient.handleIncomingMessage(approvalEventB.toString())
        testScheduler.advanceUntilIdle()

        val approvals = sessionRepo.activeApprovals.value
        assertEquals(2, approvals.size)

        val appA = approvals.find { it.hostId == host1Id && it.approval.requestId == "req_shared_1" }
        val appB = approvals.find { it.hostId == host2Id && it.approval.requestId == "req_shared_1" }

        assertNotNull(appA)
        assertNotNull(appB)
        assertEquals("powershell.exe -Command Get-Process", appA?.approval?.command)
        assertEquals("ps aux", appB?.approval?.command)
        assertEquals(RuntimeSessionId("rt_win_shared"), appA?.runtimeSessionId)
        assertEquals(RuntimeSessionId("rt_lin_shared"), appB?.runtimeSessionId)
    }

    @Test
    fun testTwoHermesHostsWithIdenticalRuntimeSessionIdDoNotCrossRouteEvents() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Windows PC", baseUrl = "http://pc:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://linux:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session1 = sessionRepo.createUnifiedSession(title = "Host 1 Session", initialHostId = host1Id)
        val session2 = sessionRepo.createUnifiedSession(title = "Host 2 Session", initialHostId = host2Id)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)!!
        val runtimeB = connectionManager.getRuntime(host2Id)!!

        // Both hosts independently return the EXACT SAME runtimeSessionId "s1"
        sessionRepo.registerRuntimeBinding(session1.id, host1Id, RuntimeSessionId("s1"))
        sessionRepo.registerRuntimeBinding(session2.id, host2Id, RuntimeSessionId("s1"))

        // Host 1 sends delta for session_id "s1"
        val eventHost1 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "s1")
                put("payload", buildJsonObject {
                    put("message_id", "m_shared")
                    put("delta", "Chunk From Host 1")
                })
            })
        }
        // Host 2 sends delta for session_id "s1"
        val eventHost2 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "s1")
                put("payload", buildJsonObject {
                    put("message_id", "m_shared")
                    put("delta", "Chunk From Host 2")
                })
            })
        }

        runtimeA.gatewayClient.handleIncomingMessage(eventHost1.toString())
        runtimeB.gatewayClient.handleIncomingMessage(eventHost2.toString())
        testScheduler.advanceUntilIdle()

        val session1Messages = sessionRepo.getSessionMessages(session1.id).value
        val session2Messages = sessionRepo.getSessionMessages(session2.id).value

        assertEquals(1, session1Messages.size)
        assertEquals("Chunk From Host 1", session1Messages[0].content)
        assertEquals(host1Id, session1Messages[0].hostId)

        assertEquals(1, session2Messages.size)
        assertEquals("Chunk From Host 2", session2Messages[0].content)
        assertEquals(host2Id, session2Messages[0].hostId)
    }

    @Test
    fun testAppRestartRestoresBindingViaDurableId() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.resume")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"durable_persisted_99","session_id":"fresh_runtime_101"}}""")
                                } else if (text.contains("prompt.submit")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"turn_id":"t_101"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Prod Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

        // Seed DB with existing session and binding from a previous app run
        val sessionId = UnifiedSessionId("session_saved_1")
        testSessionDao.insertSession(
            UnifiedSessionEntity(
                id = sessionId.value,
                title = "Restored Session",
                activeHostId = host1Id.value
            )
        )
        testSessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = sessionId.value,
                hostId = host1Id.value,
                durableSessionId = "durable_persisted_99",
                runtimeSessionId = "stale_dead_runtime_000",
                state = BindingState.OFFLINE.name
            )
        )

        val freshConnectionManager = HermesConnectionManager(
            hostDao = testHostDao,
            tokenVault = testTokenVault,
            scope = CoroutineScope(Dispatchers.Default)
        )
        val freshRepo = UnifiedSessionRepository(
            connectionManager = freshConnectionManager,
            sessionDao = testSessionDao,
            scope = CoroutineScope(Dispatchers.Default)
        )

        val host1 = HermesHost(id = host1Id, displayName = "Prod Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = HostStatus.ONLINE)
        freshConnectionManager.addHost(host1)

        val runtime = freshConnectionManager.getRuntime(host1Id)
        assertNotNull(runtime)
        runtime!!.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        val turnId = freshRepo.sendPrompt(sessionId, "Hello after restart")
        assertEquals("t_101", turnId)

        // Verify that binding was updated in DB with the fresh runtime ID
        val updatedBinding = testSessionDao.getBindingsForSession(sessionId.value).find { it.hostId == host1Id.value }
        assertNotNull(updatedBinding)
        assertEquals("durable_persisted_99", updatedBinding?.durableSessionId)
        assertEquals("fresh_runtime_101", updatedBinding?.runtimeSessionId)
        assertEquals(BindingState.RUNNING.name, updatedBinding?.state)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testAppRestartWithStateReadyCallsSessionResume() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()
        var sessionResumeCalled = false
        var resumedDurableId = ""

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.resume")) {
                                    sessionResumeCalled = true
                                    if (text.contains("valid_durable_888")) {
                                        resumedDurableId = "valid_durable_888"
                                    }
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"valid_durable_888","session_id":"fresh_runtime_777"}}""")
                                } else if (text.contains("prompt.submit")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"turn_id":"turn_ready_restart"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

        val sessionId = UnifiedSessionId("session_ready_restart")
        testSessionDao.insertSession(
            UnifiedSessionEntity(
                id = sessionId.value,
                title = "Ready Restart Test",
                activeHostId = host1Id.value
            )
        )
        // Binding in Room has state = READY, but stale runtimeSessionId from previous process run
        testSessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = sessionId.value,
                hostId = host1Id.value,
                durableSessionId = "valid_durable_888",
                runtimeSessionId = "stale_dead_runtime_999",
                state = BindingState.READY.name
            )
        )

        // New process simulation
        val freshConnectionManager = HermesConnectionManager(
            hostDao = testHostDao,
            tokenVault = testTokenVault,
            scope = CoroutineScope(Dispatchers.Default)
        )
        val freshRepo = UnifiedSessionRepository(
            connectionManager = freshConnectionManager,
            sessionDao = testSessionDao,
            scope = CoroutineScope(Dispatchers.Default)
        )

        val host1 = HermesHost(id = host1Id, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = HostStatus.ONLINE)
        freshConnectionManager.addHost(host1)

        val runtime = freshConnectionManager.getRuntime(host1Id)!!
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        // Calling sendPrompt must trigger session.resume since in-memory attachment does not exist in new process
        val turnId = freshRepo.sendPrompt(sessionId, "Hello after clean restart")
        assertEquals("turn_ready_restart", turnId)

        assertTrue("session.resume MUST be called even if persisted state was READY", sessionResumeCalled)
        assertEquals("valid_durable_888", resumedDurableId)

        val updatedBinding = testSessionDao.getBindingsForSession(sessionId.value).find { it.hostId == host1Id.value }
        assertNotNull(updatedBinding)
        assertEquals("valid_durable_888", updatedBinding?.durableSessionId)
        assertEquals("fresh_runtime_777", updatedBinding?.runtimeSessionId)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testHostReconnectMintsNewRuntimeId() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()
        var sessionCreated = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.create")) {
                                    sessionCreated = true
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"dur_conn_1","session_id":"rt_initial"}}""")
                                } else if (text.contains("session.resume")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"stored_session_id":"dur_conn_1","session_id":"rt_after_reconnect"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

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

        val session = testRepo.createUnifiedSession(title = "Reconnect Test", initialHostId = host1Id)
        val runtime = testConnectionManager.getRuntime(host1Id)!!

        // Initial connect
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        val binding1 = testRepo.ensureAttachedRuntimeSession(session.id, host1Id, runtime)
        assertEquals(RuntimeSessionId("rt_initial"), binding1.runtimeSessionId)

        // Disconnect host runtime
        runtime.disconnect()
        testSessionDao.updateBindingState(session.id.value, host1Id.value, BindingState.OFFLINE.name)

        // Reconnect host runtime
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        // Reattach after reconnect
        val binding2 = testRepo.ensureAttachedRuntimeSession(session.id, host1Id, runtime)
        assertEquals(RuntimeSessionId("rt_after_reconnect"), binding2.runtimeSessionId)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testFailedPromptSubmitDoesNotAdvanceContextSyncCursor() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.resume")) {
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"dur_fail_test","session_id":"rt_fail_test"}}""")
                                } else if (text.contains("prompt.submit")) {
                                    // Fail prompt submission with an RPC error
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","error":{"code":-32000,"message":"Model overloaded"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

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

        val host1 = HermesHost(id = host1Id, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = HostStatus.ONLINE)
        testConnectionManager.addHost(host1)

        val session = testRepo.createUnifiedSession(title = "Cursor Test", initialHostId = host1Id)

        // Pre-populate binding with syncedThroughMessageId = "msg_baseline"
        testSessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_fail_test",
                runtimeSessionId = "rt_fail_test",
                syncedThroughMessageId = "msg_baseline",
                state = BindingState.READY.name
            )
        )

        val runtime = testConnectionManager.getRuntime(host1Id)!!
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        // Attempt sendPrompt which will fail at submitPrompt
        var threw = false
        try {
            testRepo.sendPrompt(session.id, "Will fail")
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("Expected prompt submission to throw", threw)

        // Verify syncedThroughMessageId did NOT advance and remains "msg_baseline"
        val binding = testSessionDao.getBindingsForSession(session.id.value).find { it.hostId == host1Id.value }
        assertEquals("msg_baseline", binding?.syncedThroughMessageId)
        assertEquals(BindingState.ERROR.name, binding?.state)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testTransientResumeErrorDoesNotDestroyBindingOrCallCreate() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()
        var createCalled = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.resume")) {
                                    // Transient failure: 500 / -32000 Server overloaded
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","error":{"code":-32000,"message":"Transient server error"}}""")
                                } else if (text.contains("session.create")) {
                                    createCalled = true
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"stored_session_id":"dur_forbidden","session_id":"rt_forbidden"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

        val sessionId = UnifiedSessionId("session_transient_test")
        testSessionDao.insertSession(
            UnifiedSessionEntity(
                id = sessionId.value,
                title = "Transient Resume Test",
                activeHostId = host1Id.value
            )
        )
        testSessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = sessionId.value,
                hostId = host1Id.value,
                durableSessionId = "dur_preserved_123",
                runtimeSessionId = "rt_stale",
                state = BindingState.READY.name
            )
        )

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

        val host1 = HermesHost(id = host1Id, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = HostStatus.ONLINE)
        testConnectionManager.addHost(host1)

        val runtime = testConnectionManager.getRuntime(host1Id)!!
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        var threw = false
        try {
            testRepo.ensureAttachedRuntimeSession(sessionId, host1Id, runtime)
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("Expected transient resume error to throw", threw)
        assertFalse("session.create MUST NOT be called on transient resume error", createCalled)

        // Verify binding was not destroyed
        val binding = testSessionDao.getBindingsForSession(sessionId.value).find { it.hostId == host1Id.value }
        assertEquals("dur_preserved_123", binding?.durableSessionId)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testUnrecoverableResumeErrorRecreatesSession() = runBlocking(Dispatchers.Default) {
        val server = MockWebServer()
        var createCalled = false

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path == "/api/status" -> {
                        MockResponse().setResponseCode(200).setBody("""{"status":"ok","auth_required":false,"version":"1.0.0"}""")
                    }
                    path.startsWith("/api/ws") || path.startsWith("/ws") -> {
                        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                if (text.contains("session.resume")) {
                                    // Definitively unrecoverable: 404 Session not found
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a1","error":{"code":404,"message":"Session not found"}}""")
                                } else if (text.contains("session.create")) {
                                    createCalled = true
                                    webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"stored_session_id":"dur_new_fresh_999","session_id":"rt_new_fresh_999"}}""")
                                }
                            }
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        server.start()
        val wsUrl = server.url("").toString().removeSuffix("/")
        val testHostDao = FakeHostDao()
        val testSessionDao = FakeUnifiedSessionDao()
        val testTokenVault = InMemoryTokenVault()

        testHostDao.insertOrUpdateHost(HostEntity(id = host1Id.value, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = "ONLINE"))

        val sessionId = UnifiedSessionId("session_unrecoverable_test")
        testSessionDao.insertSession(
            UnifiedSessionEntity(
                id = sessionId.value,
                title = "Unrecoverable Resume Test",
                activeHostId = host1Id.value
            )
        )
        testSessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = sessionId.value,
                hostId = host1Id.value,
                durableSessionId = "dur_dead_deleted",
                runtimeSessionId = "rt_dead",
                state = BindingState.READY.name
            )
        )

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

        val host1 = HermesHost(id = host1Id, displayName = "Server", baseUrl = wsUrl, allowCleartext = true, lastKnownStatus = HostStatus.ONLINE)
        testConnectionManager.addHost(host1)

        val runtime = testConnectionManager.getRuntime(host1Id)!!
        runtime.connect()
        runtime.gatewayClient.awaitGatewayReady(5000)

        val attachedBinding = testRepo.ensureAttachedRuntimeSession(sessionId, host1Id, runtime)

        assertTrue("session.create MUST be called on unrecoverable 404 resume error", createCalled)
        assertEquals(DurableSessionId("dur_new_fresh_999"), attachedBinding.durableSessionId)
        assertEquals(RuntimeSessionId("rt_new_fresh_999"), attachedBinding.runtimeSessionId)

        // Verify Room DB binding was updated
        val binding = testSessionDao.getBindingsForSession(sessionId.value).find { it.hostId == host1Id.value }
        assertEquals("dur_new_fresh_999", binding?.durableSessionId)
        assertEquals("rt_new_fresh_999", binding?.runtimeSessionId)

        runtime.disconnect()
        server.shutdown()
    }

    @Test
    fun testStopHostADoesNotStopHostB() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Windows PC", baseUrl = "http://pc:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Linux Server", baseUrl = "http://linux:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session = sessionRepo.createUnifiedSession(title = "Targeted Stop", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        val runtimeB = connectionManager.getRuntime(host2Id)

        sessionRepo.registerRuntimeBinding(session.id, host1Id, RuntimeSessionId("rt_a"))
        sessionRepo.registerRuntimeBinding(session.id, host2Id, RuntimeSessionId("rt_b"))

        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_a",
                runtimeSessionId = "rt_a",
                state = BindingState.RUNNING.name
            )
        )
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host2Id.value,
                durableSessionId = "dur_b",
                runtimeSessionId = "rt_b",
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        // Start execution on both hosts using upstream Hermes envelope
        runtimeA!!.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.start")
                    put("session_id", "rt_a")
                    put("payload", buildJsonObject {
                        put("message_id", "msg_a")
                    })
                })
            }.toString()
        )
        runtimeB!!.gatewayClient.handleIncomingMessage(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.start")
                    put("session_id", "rt_b")
                    put("payload", buildJsonObject {
                        put("message_id", "msg_b")
                    })
                })
            }.toString()
        )
        testScheduler.advanceUntilIdle()

        assertTrue(sessionRepo.getHostExecuting(session.id, host1Id).value)
        assertTrue(sessionRepo.getHostExecuting(session.id, host2Id).value)

        // Stop Host A specifically
        sessionRepo.interruptHost(session.id, host1Id)
        testScheduler.advanceUntilIdle()

        // Host A execution should be false, Host B execution MUST still be true
        assertFalse("Host A should be stopped", sessionRepo.getHostExecuting(session.id, host1Id).value)
        assertTrue("Host B should still be running", sessionRepo.getHostExecuting(session.id, host2Id).value)
    }
}
