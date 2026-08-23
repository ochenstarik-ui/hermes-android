package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalRoutingTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var sessionRepo: UnifiedSessionRepository

    private val host1Id = HermesHostId("server-prod")
    private val host2Id = HermesHostId("server-dev")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher)
        )

        sessionRepo = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testApprovalAttributionAndRemoval() = runTest(testDispatcher) {
        val host1 = HermesHost(id = host1Id, displayName = "Prod Server", baseUrl = "http://prod:9119")
        val host2 = HermesHost(id = host2Id, displayName = "Dev Server", baseUrl = "http://dev:9119")

        connectionManager.addHost(host1)
        connectionManager.addHost(host2)
        testScheduler.advanceUntilIdle()

        val runtime1 = connectionManager.getRuntime(host1Id)
        val runtime2 = connectionManager.getRuntime(host2Id)

        assertNotNull(runtime1)
        assertNotNull(runtime2)

        // Simulate approval request from Prod Server
        val prodEventJson = buildJsonObject {
            put("method", "event")
            put("params", buildJsonObject {
                put("event", "approval.request")
                put("request_id", "req_prod_1")
                put("command", "systemctl restart nginx")
                put("description", "Restart web server")
            })
        }
        runtime1?.gatewayClient?.handleIncomingMessage(prodEventJson.toString())

        // Simulate approval request from Dev Server
        val devEventJson = buildJsonObject {
            put("method", "event")
            put("params", buildJsonObject {
                put("event", "approval.request")
                put("request_id", "req_dev_1")
                put("command", "docker compose down")
                put("description", "Stop containers")
            })
        }
        runtime2?.gatewayClient?.handleIncomingMessage(devEventJson.toString())
        testScheduler.advanceUntilIdle()

        val approvals = sessionRepo.activeApprovals.value
        assertEquals(2, approvals.size)

        val prodApproval = approvals.find { it.hostId == host1Id }
        val devApproval = approvals.find { it.hostId == host2Id }

        assertNotNull(prodApproval)
        assertNotNull(devApproval)
        assertEquals("Prod Server", prodApproval?.hostDisplayName)
        assertEquals("Dev Server", devApproval?.hostDisplayName)
        assertEquals("systemctl restart nginx", prodApproval?.approval?.command)

        // Responding to prod approval removes it while keeping dev approval
        sessionRepo.respondApproval(host1Id, "req_prod_1", "once", false)
        testScheduler.advanceUntilIdle()

        val remainingApprovals = sessionRepo.activeApprovals.value
        assertEquals(1, remainingApprovals.size)
        assertEquals(host2Id, remainingApprovals.first().hostId)
    }
}
