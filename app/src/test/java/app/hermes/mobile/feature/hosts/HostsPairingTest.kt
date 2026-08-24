package app.hermes.mobile.feature.hosts

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.pairing.HermesPairingPayload
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.TokenVault
import app.hermes.mobile.core.storage.HostDao
import app.hermes.mobile.core.storage.HostEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class HostsPairingTest {

    private lateinit var viewModel: HostsViewModel
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var tokenVault: TokenVault
    private lateinit var hostDao: HostDao
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        hostDao = mockk(relaxed = true)
        tokenVault = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        coEvery { connectionManager.hostDao } returns hostDao
        
        viewModel = HostsViewModel(connectionManager, tokenVault, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testPairingNewHostInsertsHost() = runTest {
        val hostId = UUID.randomUUID().toString()
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = hostId,
            name = "New Test Server",
            host = "192.168.1.10",
            port = 9119,
            scheme = "https",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "nonce"
        )
        
        coEvery { hostDao.getHost(hostId) } returns null
        coEvery { connectionManager.addHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)

        viewModel.confirmPairing(payload, allowCleartext = false)

        coVerify { 
            connectionManager.addHost(match { 
                it.id.value == hostId && 
                it.displayName == "New Test Server" && 
                it.baseUrl == "https://192.168.1.10:9119" &&
                !it.allowCleartext
            }) 
        }
        coVerify { connectionManager.connectHost(HermesHostId(hostId)) }
    }

    @Test
    fun testPairingExistingHostUpdatesEndpointWithoutDuplicating() = runTest {
        val hostId = UUID.randomUUID().toString()
        val existingEntity = HostEntity(
            id = hostId,
            displayName = "Old Name",
            baseUrl = "http://192.168.1.5:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 1000L,
            lastKnownStatus = "OFFLINE"
        )
        
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = hostId,
            name = "Updated Server Name",
            host = "192.168.1.20",
            port = 9119,
            scheme = "http",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "nonce"
        )
        
        coEvery { hostDao.getHost(hostId) } returns existingEntity
        coEvery { connectionManager.updateHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)

        viewModel.confirmPairing(payload, allowCleartext = true)

        coVerify(exactly = 0) { connectionManager.addHost(any()) }
        coVerify { 
            connectionManager.updateHost(match { 
                it.id.value == hostId && 
                it.displayName == "Updated Server Name" && 
                it.baseUrl == "http://192.168.1.20:9119" &&
                it.allowCleartext
            }) 
        }
        coVerify { connectionManager.connectHost(HermesHostId(hostId)) }
    }

    @Test
    fun testExistingTokensPreservedOnHostUpdate() = runTest {
        val hostId = UUID.randomUUID().toString()
        val existingEntity = HostEntity(
            id = hostId,
            displayName = "Old Name",
            baseUrl = "http://192.168.1.5:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 1000L,
            lastKnownStatus = "OFFLINE"
        )
        
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = hostId,
            name = "Updated Server Name",
            host = "192.168.1.5",
            port = 9119,
            scheme = "http",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "nonce"
        )
        
        coEvery { hostDao.getHost(hostId) } returns existingEntity
        coEvery { connectionManager.updateHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)
        
        viewModel.confirmPairing(payload, allowCleartext = true)
        
        coVerify(exactly = 0) { tokenVault.clearTokens(any()) }
        coVerify(exactly = 0) { connectionManager.disconnectHost(any()) }
    }

    @Test
    fun testExistingTokensClearedOnEndpointChange() = runTest {
        val hostId = UUID.randomUUID().toString()
        val existingEntity = HostEntity(
            id = hostId,
            displayName = "Old Name",
            baseUrl = "http://192.168.1.5:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 1000L,
            lastKnownStatus = "OFFLINE"
        )
        
        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = hostId,
            name = "Updated Server Name",
            host = "192.168.1.20",
            port = 9119,
            scheme = "http",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "nonce"
        )
        
        coEvery { hostDao.getHost(hostId) } returns existingEntity
        coEvery { connectionManager.updateHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)
        
        viewModel.confirmPairing(payload, allowCleartext = true)
        
        coVerify { tokenVault.clearTokens(hostId) }
        coVerify { connectionManager.disconnectHost(app.hermes.mobile.core.model.HermesHostId(hostId)) }
    }
}
