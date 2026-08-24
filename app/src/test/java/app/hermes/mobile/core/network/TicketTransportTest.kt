package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.GatewayEvent
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class TicketTransportTest {

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
    fun testTicketTransportPreservesSpecialCharactersWithoutCorruption() {
        runBlocking {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}""")
                    }
                })
            )

            val rawTicket = "ticket+with#special&chars/test=123"
            val wsUrl = "ws://${server.hostName}:${server.port}/api/ws"

            client.connect(wsUrl, ticket = rawTicket, allowCleartext = true)
            client.awaitGatewayReady(5000)

            val recordedRequest = server.takeRequest()
            val path = recordedRequest.path ?: ""
            val authHeader = recordedRequest.getHeader("Authorization")

            // Either passed via Authorization header (preferred) or safely URL-encoded in query without truncation
            val hasValidAuthHeader = authHeader != null && authHeader == "Bearer $rawTicket"
            val hasEncodedQuery = path.contains("ticket=") && !path.contains("#") && !path.contains("&chars") &&
                    (path.contains(java.net.URLEncoder.encode(rawTicket, "UTF-8")))

            // Verification: Ticket must not be truncated by # or & in URL, and must be delivered intact
            assertTrue(
                "Ticket transport failed! URL path was: $path, Authorization header: $authHeader",
                hasValidAuthHeader || hasEncodedQuery
            )

            // Specifically verify query string does not expose raw unencoded special chars
            assertFalse("Raw unencoded '#' in URL path corrupts WebSocket handshake", path.contains("#"))
        }
    }
}
