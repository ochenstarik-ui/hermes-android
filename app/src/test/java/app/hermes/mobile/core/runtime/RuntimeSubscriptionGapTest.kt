package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostGatewayEvent
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSubscriptionGapTest {

    @Test
    fun testInitialEventDeliveredWithoutSubscriptionRaceGap() = runBlocking {
        val hostDao = FakeHostDao()
        val tokenVault = InMemoryTokenVault()
        val restClient = HermesRestClient()

        val host = HermesHost(
            id = HermesHostId("h-gap-1"),
            displayName = "Gap Host",
            baseUrl = "http://localhost:8080",
            allowCleartext = true,
            enabled = true,
            lastKnownStatus = HostStatus.OFFLINE
        )

        // Custom factory that immediately emits an event as soon as runtime is created
        var manager: HermesConnectionManager? = null
        manager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            restClient = restClient,
            runtimeFactory = { parentScope, h ->
                val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val rt = HermesHostRuntime(
                    initialHost = h,
                    restClient = restClient,
                    tokenVault = tokenVault,
                    scope = childScope
                )
                // Runtime fires an immediate event right upon instantiation
                rt.gatewayClient.handleIncomingMessage(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0"}}}"""
                )
                rt
            }
        )

        val receivedEvents = ConcurrentLinkedQueue<HostGatewayEvent>()
        val collectorJob = launch(Dispatchers.Default) {
            manager.allEvents.collect { event ->
                receivedEvents.add(event)
            }
        }

        // Create runtime
        val runtime = manager.getOrCreateRuntime(host)
        assertNotNull(runtime)

        // Verify the event emitted during/immediately after creation was collected
        val received = withTimeout(3000) {
            while (receivedEvents.isEmpty()) {
                delay(20)
            }
            receivedEvents.poll()
        }

        assertNotNull("Initial event must not be dropped due to subscription gap", received)
        assertEquals(host.id, received?.hostId)
        assertTrue(received?.event is GatewayEvent.GatewayReadyEvent)

        collectorJob.cancel()
    }

    @Test
    fun testConcurrentGetOrCreateRuntimeDoesNotDeadlock() = runBlocking {
        val hostDao = FakeHostDao()
        val tokenVault = InMemoryTokenVault()
        val manager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault
        )

        val host = HermesHost(
            id = HermesHostId("h-concurrent"),
            displayName = "Concurrent Host",
            baseUrl = "http://localhost:9090",
            allowCleartext = true,
            enabled = true
        )

        val threadPool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val runtimes = ConcurrentLinkedQueue<HermesHostRuntime>()

        val jobs = (1..30).map {
            launch(threadPool) {
                val rt = manager.getOrCreateRuntime(host)
                runtimes.add(rt)
            }
        }

        withTimeout(5000) {
            jobs.joinAll()
        }
        threadPool.close()

        assertEquals(30, runtimes.size)
        val firstRt = runtimes.peek()
        assertTrue("All returned runtimes must be the identical instance", runtimes.all { it === firstRt })
    }
}
