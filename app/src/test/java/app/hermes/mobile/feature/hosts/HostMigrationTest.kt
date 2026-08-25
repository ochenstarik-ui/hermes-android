package app.hermes.mobile.feature.hosts

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.pairing.CanonicalEndpoint
import app.hermes.mobile.core.pairing.PairingPayloadV1
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HostMigrationTest {

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
    fun testLegacyCleartextHostEntityModelCompatibility() {
        val legacyId = UUID.randomUUID().toString()
        val legacyEntity = HostEntity(
            id = legacyId,
            displayName = "Legacy LAN Node",
            baseUrl = "http://192.168.1.50:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 123456789L,
            lastKnownStatus = "OFFLINE",
            certificateFingerprint = null
        )

        assertEquals("http://192.168.1.50:9119", legacyEntity.baseUrl)
        assertNull(legacyEntity.certificateFingerprint)
        assertEquals(true, legacyEntity.allowCleartext)
    }

    @Test
    fun testLegacyHostUpgradedToV2HttpsWithFingerprint() = runTest {
        val hostId = UUID.randomUUID().toString()
        val legacyEntity = HostEntity(
            id = hostId,
            displayName = "Legacy Host",
            baseUrl = "http://192.168.1.50:9119",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 1000L,
            lastKnownStatus = "OFFLINE",
            certificateFingerprint = null
        )

        val v2Payload = PairingPayloadV1(
            v = 2,
            type = "hermes-pair",
            hostId = hostId,
            name = "Upgraded TLS Host",
            host = "192.168.1.50",
            port = 9119,
            scheme = "https",
            expiresAt = (System.currentTimeMillis() / 1000) + 3600,
            nonce = "AQIDBAUGBwgJCgsMDQ4PEA",
            fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        )

        coEvery { hostDao.getHost(hostId) } returns legacyEntity
        coEvery { connectionManager.updateHost(any()) } returns Unit
        coEvery { connectionManager.connectHost(any()) } returns Result.success(Unit)

        viewModel.confirmPairing(v2Payload, allowCleartext = false)

        // Scheme changed from http to https: verify old tokens are cleared and disconnected
        coVerify { tokenVault.clearTokens(hostId) }
        coVerify { connectionManager.disconnectHost(HermesHostId(hostId)) }

        // Host updated with new HTTPS baseUrl and certificate fingerprint
        coVerify {
            connectionManager.updateHost(match {
                it.id.value == hostId &&
                it.displayName == "Upgraded TLS Host" &&
                it.baseUrl == "https://192.168.1.50:9119" &&
                it.certificateFingerprint == "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99" &&
                !it.allowCleartext
            })
        }
        coVerify { connectionManager.connectHost(HermesHostId(hostId)) }
    }

    @Test
    fun testCanonicalEndpointDetectsSchemeMigration() {
        val httpEndpoint = CanonicalEndpoint.fromBaseUrl("http://192.168.1.50:9119")
        val httpsEndpoint = CanonicalEndpoint.fromBaseUrl("https://192.168.1.50:9119")

        assertNotEquals(httpEndpoint, httpsEndpoint)
        assertEquals("http", httpEndpoint.scheme)
        assertEquals("https", httpsEndpoint.scheme)
        assertEquals(9119, httpEndpoint.port)
        assertEquals(9119, httpsEndpoint.port)
    }
}
