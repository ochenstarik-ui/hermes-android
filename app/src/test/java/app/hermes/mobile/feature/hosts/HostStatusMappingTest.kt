package app.hermes.mobile.feature.hosts

import app.hermes.mobile.core.model.HostStatus
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HostStatusMappingTest {

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
    fun testFromStringOrOfflineSafelyParsesStandardAndUnknownStatuses() {
        assertEquals(HostStatus.ONLINE, HostStatus.fromStringOrOffline("ONLINE"))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline("OFFLINE"))
        assertEquals(HostStatus.CONNECTING, HostStatus.fromStringOrOffline("CONNECTING"))
        assertEquals(HostStatus.AUTH_REQUIRED, HostStatus.fromStringOrOffline("AUTH_REQUIRED"))
        assertEquals(HostStatus.AUTH_EXPIRED, HostStatus.fromStringOrOffline("AUTH_EXPIRED"))
        assertEquals(HostStatus.ERROR, HostStatus.fromStringOrOffline("ERROR"))

        // Unknown, corrupted, empty, null values must fall back to OFFLINE without throwing
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline(null))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline(""))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline("   "))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline("UNKNOWN_STATUS"))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline("CORRUPTED_VALUE_123"))
        assertEquals(HostStatus.OFFLINE, HostStatus.fromStringOrOffline("online")) // case sensitive or safe fallback
    }

    @Test
    fun testPairingExistingHostWithCorruptedStatusDoesNotCrash() = runTest {
        val hostId = UUID.randomUUID().toString()
        val existingEntity = HostEntity(
            id = hostId,
            displayName = "Server With Corrupted Status",
            baseUrl = "http://192.168.1.50:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 1000L,
            lastKnownStatus = "CORRUPTED_STATUS_IN_DB"
        )

        val payload = HermesPairingPayload(
            v = 1,
            type = "hermes-pair",
            hostId = hostId,
            name = "Server With Corrupted Status",
            host = "192.168.1.50",
            port = 9119,
            scheme = "http",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "nonce"
        )

        coEvery { hostDao.getHost(hostId) } returns existingEntity
        coEvery { connectionManager.updateHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)

        // Must not throw IllegalArgumentException: No enum constant app.hermes.mobile.core.model.HostStatus.CORRUPTED_STATUS_IN_DB
        viewModel.confirmPairing(payload, allowCleartext = true)

        coVerify {
            connectionManager.updateHost(match {
                it.id.value == hostId && it.lastKnownStatus == HostStatus.OFFLINE
            })
        }
    }
}
