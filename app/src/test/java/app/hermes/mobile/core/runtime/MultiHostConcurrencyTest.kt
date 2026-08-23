package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiHostConcurrencyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        tokenVault = InMemoryTokenVault()
        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testMultiHostIsolationAndDisconnect() = runTest(testDispatcher) {
        val hostA = HermesHost(
            id = HermesHostId("host-a"),
            displayName = "Host Alpha",
            baseUrl = "http://10.0.0.1:9119",
            lastKnownStatus = HostStatus.ONLINE
        )
        val hostB = HermesHost(
            id = HermesHostId("host-b"),
            displayName = "Host Beta",
            baseUrl = "http://10.0.0.2:9119",
            lastKnownStatus = HostStatus.ONLINE
        )

        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(hostA.id)
        val runtimeB = connectionManager.getRuntime(hostB.id)

        assertNotNull(runtimeA)
        assertNotNull(runtimeB)

        // Disconnecting Host A must not affect Host B
        runtimeA?.disconnect()
        testScheduler.advanceUntilIdle()

        assertEquals(HostStatus.OFFLINE, runtimeA?.status?.value)
        assertEquals(ConnectionState.Disconnected, runtimeA?.connectionState?.value)

        // Runtime B remains unchanged
        assertNotNull(connectionManager.getRuntime(hostB.id))
    }

    @Test
    fun testActiveHostSwitching() = runTest(testDispatcher) {
        val host1 = HermesHost(id = HermesHostId("h1"), displayName = "H1", baseUrl = "http://1.1.1.1")
        val host2 = HermesHost(id = HermesHostId("h2"), displayName = "H2", baseUrl = "http://2.2.2.2")

        connectionManager.addHost(host1)
        connectionManager.addHost(host2)
        testScheduler.advanceUntilIdle()

        assertEquals(HermesHostId("h1"), connectionManager.activeHostId.value)

        connectionManager.switchActiveHost(HermesHostId("h2"))
        assertEquals(HermesHostId("h2"), connectionManager.activeHostId.value)
    }
}
