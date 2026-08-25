package app.hermes.mobile.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

class ReadyDeferredRaceTest {

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
    fun testAwaitGatewayReadyDoesNotHangOnStaleDeferredWhenConnectReinvoked() = runBlocking {
        // Server 1 accepts socket but never sends gateway.ready
        server1.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Intentionally hang without gateway.ready
                }
            })
        )

        // Server 2 accepts socket and sends gateway.ready
        val server2WsDeferred = CompletableDeferred<WebSocket>()
        server2.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    server2WsDeferred.complete(webSocket)
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"2.0.0"}}}""")
                }
            })
        )

        val wsUrl1 = "ws://${server1.hostName}:${server1.port}/api/ws"
        val wsUrl2 = "ws://${server2.hostName}:${server2.port}/api/ws"

        // 1. Connect to Server 1
        client.connect(wsUrl1, allowCleartext = true)

        val awaiterStarted = CompletableDeferred<Unit>()
        val awaiter = async {
            awaiterStarted.complete(Unit)
            try {
                client.awaitGatewayReady(4000)
                true
            } catch (e: Exception) {
                false
            }
        }

        awaiterStarted.await()

        // 3. Immediately re-invoke connect to Server 2 before Server 1 completes
        client.connect(wsUrl2, allowCleartext = true)

        // 4. Awaiting on the active connection must succeed promptly when Server 2 sends gateway.ready
        val activeAwaiter = async {
            client.awaitGatewayReady(4000)
            true
        }

        val ready = withTimeout(4000) {
            activeAwaiter.await()
        }

        assertTrue("Active connection awaitGatewayReady must succeed", ready)
        assertEquals("Active connection state must be Connected", ConnectionState.Connected, client.connectionState.value)
    }

    @Test
    fun testAwaitGatewayReadyReturnsImmediatelyIfAlreadyConnected() = runBlocking {
        server1.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                }
            })
        )

        client.connect("ws://${server1.hostName}:${server1.port}/api/ws", allowCleartext = true)
        client.awaitGatewayReady(5000)
        assertEquals(ConnectionState.Connected, client.connectionState.value)

        // Calling awaitGatewayReady again when already Connected should return immediately (within 100ms)
        withTimeout(100) {
            client.awaitGatewayReady(5000)
        }
        assertTrue(true)
    }
}
