package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.JsonRpcResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.IOException

class StaleSocketIsolationTest {

    private lateinit var server1: MockWebServer
    private lateinit var server2: MockWebServer
    private lateinit var client: JsonRpcGatewayClient

    @Before
    fun setUp() {
        server1 = MockWebServer()
        server1.start()
        server2 = MockWebServer()
        server2.start()
        client = JsonRpcGatewayClient()
    }

    @After
    fun tearDown() {
        client.disconnect()
        try {
            server1.shutdown()
        } catch (_: Exception) {}
        try {
            server2.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun testStaleSocketFailureDoesNotTransitionActiveConnectionStateToFailed() = runBlocking {
        var server1Ws: WebSocket? = null
        var server2Ws: WebSocket? = null

        server1.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    server1Ws = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                }
            })
        )

        val server2Received = CompletableDeferred<String>()
        server2.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    server2Ws = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"2.0.0"}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    server2Received.complete(text)
                }
            })
        )

        // 1. Connect to server 1
        client.connect("ws://${server1.hostName}:${server1.port}/api/ws", allowCleartext = true)
        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // 2. Reconnect / connect to server 2
        client.connect("ws://${server2.hostName}:${server2.port}/api/ws", allowCleartext = true)
        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // 3. Send request to server 2
        val requestDeferred = async(Dispatchers.IO) {
            client.sendRequest("test.ping")
        }

        withTimeout(5000) {
            server2Received.await()
        }

        // 4. Force failure / abrupt shutdown on stale server 1 socket
        server1Ws?.close(1001, "Going away")
        server1.shutdown()

        // Give a moment for OkHttp to deliver stale socket failure/close callback
        kotlinx.coroutines.delay(200)

        // 5. Active connection state must still be Connected, NOT Failed
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // 6. Server 2 responds to the pending request
        server2Ws?.send("""{"jsonrpc":"2.0","id":"a1","result":{"pong":true}}""")

        val response = withTimeout(5000) {
            requestDeferred.await()
        }
        assertNotNull(response)
        assertEquals("a1", response.id)
        assertEquals(ConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun testStaleSocketFailureDoesNotAbortPendingRequestsOfActiveConnection() = runBlocking {
        var server1Ws: WebSocket? = null
        var server2Ws: WebSocket? = null

        server1.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    server1Ws = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                }
            })
        )

        val server2MsgDeferred = CompletableDeferred<String>()
        server2.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    server2Ws = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"2.0.0"}}}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    server2MsgDeferred.complete(text)
                }
            })
        )

        client.connect("ws://${server1.hostName}:${server1.port}/api/ws", allowCleartext = true)
        client.awaitGatewayReady(5000)

        client.connect("ws://${server2.hostName}:${server2.port}/api/ws", allowCleartext = true)
        client.awaitGatewayReady(5000)

        val pendingCall = async(Dispatchers.IO) {
            client.sendRequest("session.create")
        }

        server2MsgDeferred.await()

        // Induce socket failure on server 1
        server1Ws?.close(1001, "Going away")
        server1.shutdown()

        kotlinx.coroutines.delay(200)

        // Ensure active connection is not in Failed state
        assertTrue(client.connectionState.value is ConnectionState.Connected)

        // Complete the pending request from server 2
        server2Ws?.send("""{"jsonrpc":"2.0","id":"a1","result":{"stored_session_id":"sess_active","session_id":"rt_active"}}""")

        val result = withTimeout(5000) {
            pendingCall.await()
        }
        assertNotNull(result.result)
    }
}
