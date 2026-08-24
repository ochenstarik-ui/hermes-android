package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostGatewayEvent
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class EventOrderingTest {

    @Test
    fun test500SequentialDeltasPreserveFifoOrderingAcrossAll3Hops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hostId = HermesHostId("test-host-ordering")
        val host = HermesHost(id = hostId, displayName = "Ordering Host", baseUrl = "http://ordering-host:9119")

        val tokenVault = InMemoryTokenVault()
        val hostDao = FakeHostDao()

        // Hop 1: GatewayClient
        val gatewayClient = JsonRpcGatewayClient(scope = scope)

        // Hop 2: HostRuntime
        val runtime = HermesHostRuntime(
            initialHost = host,
            gatewayClient = gatewayClient,
            tokenVault = tokenVault,
            scope = scope
        )

        // Hop 3: ConnectionManager
        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = scope,
            runtimeFactory = { _, _ -> runtime }
        )

        connectionManager.addHost(host)

        val totalEvents = 500
        val receivedDeltas = mutableListOf<String>()
        val allReceivedDeferred = CompletableDeferred<Unit>()

        // Collect from Hop 3 (HermesConnectionManager.allEvents)
        val job = scope.launch {
            connectionManager.allEvents.collect { hostGatewayEvent ->
                val event = hostGatewayEvent.event
                if (event is GatewayEvent.MessageDeltaEvent) {
                    receivedDeltas.add(event.delta)
                    if (receivedDeltas.size == totalEvents) {
                        allReceivedDeferred.complete(Unit)
                    }
                }
            }
        }

        // Give subscription a moment to establish
        kotlinx.coroutines.delay(100)

        val expectedBuilder = StringBuilder()
        val eventJsons = (1..totalEvents).map { i ->
            val chunk = "chunk-$i;"
            expectedBuilder.append(chunk)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.delta")
                    put("session_id", "rt_ordering_1")
                    put("payload", buildJsonObject {
                        put("message_id", "msg_order_1")
                        put("delta", chunk)
                    })
                })
            }.toString()
        }

        // Rapid sequential emission from producer thread simulating WebSocket frames
        for (jsonStr in eventJsons) {
            gatewayClient.handleIncomingMessage(jsonStr)
        }

        withTimeout(15_000) {
            allReceivedDeferred.await()
        }

        job.cancel()

        val expectedString = expectedBuilder.toString()
        val actualString = receivedDeltas.joinToString("")

        assertEquals("All 500 events must be received", totalEvents, receivedDeltas.size)
        assertEquals("Concatenated deltas must match byte-for-byte in exact FIFO order", expectedString, actualString)
    }
}
