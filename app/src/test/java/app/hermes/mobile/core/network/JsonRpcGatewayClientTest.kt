package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.RuntimeSessionId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JsonRpcGatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JsonRpcGatewayClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JsonRpcGatewayClient()
    }

    @After
    fun tearDown() {
        client.disconnect()
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    @Test
    fun testWebSocketConnectAndRpcExchange() = runBlocking {
        var serverWebSocket: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWebSocket = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":0}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // When receiving session.create, respond with result
                    if (text.contains("session.create")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"durable_123","session_id":"runtime_456"}}""")
                    }
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        client.connect(wsUrl, ticket = "sample_ticket_123", allowCleartext = true)

        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        val res = client.createSession(source = "android")
        assertEquals(DurableSessionId("durable_123"), res.durableId)
        assertEquals(RuntimeSessionId("runtime_456"), res.runtimeId)

        serverWebSocket?.close(1000, "done")
        client.disconnect()
    }

    @Test
    fun testGatewayReadyStateTransition() = runBlocking {
        var serverWebSocket: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWebSocket = webSocket
                    // Do not send gateway.ready immediately
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        client.connect(wsUrl, allowCleartext = true)

        // Give WS a moment to open transport
        var retries = 0
        while (serverWebSocket == null && retries < 50) {
            kotlinx.coroutines.delay(50)
            retries++
        }
        
        // Must still be Connecting before gateway.ready is received
        assertEquals(ConnectionState.Connecting, client.connectionState.value)

        // Send gateway.ready
        serverWebSocket?.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":1}}}""")

        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        serverWebSocket?.close(1000, "done")
        client.disconnect()
    }

    @Test
    fun testEventDispatchingFromWebSocket() = runBlocking {
        var serverWebSocket: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWebSocket = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                    // Send an incoming server notification/event
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"rt_test","payload":{"message_id":"m100","delta":"Streaming token"}}}""")
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        
        // Start subscription BEFORE connecting, so we don't miss the event
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5000) {
                client.events.first { it is GatewayEvent.MessageDeltaEvent }
            }
        }
        
        client.connect(wsUrl, allowCleartext = true)
        client.awaitGatewayReady(5000)

        val event = eventDeferred.await()

        assertTrue(event is GatewayEvent.MessageDeltaEvent)
        val deltaEvent = event as GatewayEvent.MessageDeltaEvent
        assertEquals("m100", deltaEvent.messageId)
        assertEquals("Streaming token", deltaEvent.delta)
        assertEquals("rt_test", deltaEvent.sessionId)

        serverWebSocket?.close(1000, "done")
        client.disconnect()
    }

    @Test
    fun testRpcMethodsSendSessionId() = runBlocking {
        var serverWebSocket: WebSocket? = null
        val receivedTexts = mutableListOf<String>()

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWebSocket = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":0}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedTexts.add(text)
                    if (text.contains("session.resume")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"dur_1","session_id":"rt_1"}}""")
                    } else if (text.contains("prompt.submit")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a2","result":{"turn_id":"t_1"}}""")
                    } else if (text.contains("session.interrupt")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a3","result":{"status":"ok"}}""")
                    } else if (text.contains("approval.respond")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a4","result":{"status":"ok"}}""")
                    } else if (text.contains("clarify.respond")) {
                        webSocket.send("""{"jsonrpc":"2.0","id":"a5","result":{"status":"ok"}}""")
                    }
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        client.connect(wsUrl, allowCleartext = true)
        client.awaitGatewayReady(5000)

        val resumeRes = client.resumeSession(DurableSessionId("dur_1"))
        assertEquals(DurableSessionId("dur_1"), resumeRes.durableId)
        assertEquals(RuntimeSessionId("rt_1"), resumeRes.runtimeId)

        val promptRes = client.submitPrompt(RuntimeSessionId("rt_1"), "Hello")
        assertEquals("t_1", promptRes.turnId)

        val interruptRes = client.interruptSession(RuntimeSessionId("rt_1"))
        assertTrue(interruptRes)

        val approvalRes = client.respondApproval("rt_1", "req_1", "once", false)
        assertTrue(approvalRes)

        val clarifyRes = client.respondClarify("req_2", "42", "q_1")
        assertTrue(clarifyRes)

        // Verify wire contents sent over WebSocket
        assertTrue(receivedTexts[0].contains("\"session_id\":\"dur_1\""))
        assertTrue(receivedTexts[1].contains("\"session_id\":\"rt_1\""))
        assertTrue(receivedTexts[1].contains("\"text\":\"Hello\""))
        assertTrue(receivedTexts[2].contains("\"session_id\":\"rt_1\""))
        assertTrue(receivedTexts[3].contains("\"session_id\":\"rt_1\""))
        assertTrue(receivedTexts[3].contains("\"request_id\":\"req_1\""))
        assertTrue(receivedTexts[4].contains("\"question_id\":\"q_1\""))

        serverWebSocket?.close(1000, "done")
        client.disconnect()
    }
}
