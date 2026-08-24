package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConnectReadinessTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun testConnectDoesNotReturnSuccessUntilGatewayReadyReceived() = runBlocking {
        val serverWsDeferred = CompletableDeferred<WebSocket>()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.contains("/api/status")) {
                    return MockResponse().setResponseCode(200).setBody("""{"version":"1.0.0","auth_required":false}""")
                }
                if (path.contains("/api/ws")) {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            serverWsDeferred.complete(webSocket)
                            // Intentionally DO NOT send gateway.ready yet
                        }
                    })
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val host = HermesHost(
            id = HermesHostId("h-readiness"),
            displayName = "Readiness Host",
            baseUrl = "http://${server.hostName}:${server.port}",
            allowCleartext = true,
            enabled = true,
            lastKnownStatus = HostStatus.OFFLINE
        )

        val runtime = HermesHostRuntime(
            initialHost = host,
            tokenVault = InMemoryTokenVault()
        )

        // Launch connect() asynchronously
        val connectJob = async {
            runtime.connect()
        }

        // Wait for socket to be opened on server side
        val serverWs = withTimeout(5000) {
            serverWsDeferred.await()
        }

        // Wait 300ms: during this time connectJob must NOT be completed because gateway.ready was not sent
        delay(300)
        assertFalse("connect() must not complete before gateway.ready is received", connectJob.isCompleted)
        assertEquals("Host status must be CONNECTING before gateway.ready", HostStatus.CONNECTING, runtime.status.value)

        // Now send gateway.ready from server
        serverWs.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":0}}}""")

        // Now connect() must complete with success
        val result = withTimeout(5000) {
            connectJob.await()
        }

        assertTrue("connect() must succeed after gateway.ready", result.isSuccess)
        assertEquals("Host status must be ONLINE after gateway.ready", HostStatus.ONLINE, runtime.status.value)

        runtime.close()
    }
}
