package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSingleFlightTest {

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
    fun testParallelReconnectTriggersResultInSingleActiveReconnectLoop() = runBlocking {
        val concurrentRequests = AtomicInteger(0)
        val maxConcurrentRequests = AtomicInteger(0)
        val totalStatusRequests = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.contains("/api/status")) {
                    totalStatusRequests.incrementAndGet()
                    val current = concurrentRequests.incrementAndGet()
                    var max = maxConcurrentRequests.get()
                    while (current > max && !maxConcurrentRequests.compareAndSet(max, current)) {
                        max = maxConcurrentRequests.get()
                    }
                    Thread.sleep(100) // Hold request briefly to expose concurrency
                    concurrentRequests.decrementAndGet()
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"version":"1.0.0","auth_required":false}""")
                }
                if (path.contains("/api/ws")) {
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            // Immediately close to trigger reconnect
                            webSocket.close(1001, "Simulated disconnect")
                        }
                    })
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()

        val host = HermesHost(
            id = HermesHostId("host-sf"),
            displayName = "Single Flight Host",
            baseUrl = "http://${server.hostName}:${server.port}",
            allowCleartext = true,
            enabled = true,
            lastKnownStatus = HostStatus.ONLINE
        )

        val runtime = HermesHostRuntime(
            initialHost = host,
            tokenVault = InMemoryTokenVault()
        )

        // Connect first to enable autoReconnect
        runtime.connect()

        // Fire 20 parallel failure / reconnect triggers across a multi-thread pool
        val pool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val jobs = (1..20).map {
            runtime.scope.launch(pool) {
                runtime.scheduleReconnect()
            }
        }
        jobs.joinAll()
        pool.close()

        // Wait for reconnects to process
        kotlinx.coroutines.delay(1500)

        runtime.close()

        // Under single flight, reconnect attempts are serialized, max concurrent requests must be <= 1
        assertEquals("Max concurrent connect requests must be at most 1", 1, maxConcurrentRequests.get())
    }
}
