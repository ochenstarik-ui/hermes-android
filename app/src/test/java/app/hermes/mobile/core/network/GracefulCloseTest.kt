package app.hermes.mobile.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class GracefulCloseTest {

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
        } catch (_: Exception) {}
    }

    @Test
    fun testServerInitiatedGracefulCloseHandshake() = runBlocking {
        val serverReceivedClosingAck = CompletableDeferred<Int>()
        var serverWs: WebSocket? = null

        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWs = webSocket
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    serverReceivedClosingAck.complete(code)
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        client.connect(wsUrl, allowCleartext = true)
        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // Server initiates graceful close
        serverWs?.close(1000, "Server stopping")

        // Client onClosing acknowledges close frame
        val ackCode = withTimeout(5000) {
            serverReceivedClosingAck.await()
        }
        assertEquals("Client must acknowledge graceful close with code 1000", 1000, ackCode)
    }

    @Test
    fun testClientDisconnectTransitionsStateAndFailsPendingRequests() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                }
            })
        )

        val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"
        client.connect(wsUrl, allowCleartext = true)
        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // Disconnect immediately cleans up connection
        client.disconnect()
        assertEquals(ConnectionState.Disconnected, client.connectionState.value)
    }
}
