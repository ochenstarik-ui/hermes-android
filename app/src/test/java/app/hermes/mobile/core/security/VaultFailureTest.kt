package app.hermes.mobile.core.security

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.model.NativeAuthTokens
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.runtime.HermesHostRuntime
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VaultFailureTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun testCorruptedVaultTransitionsToAuthRequiredWithoutCrash() = runBlocking {
        // Vault that simulates keystore decryption failure / corruption by returning null tokens
        val corruptedVault = object : TokenVault {
            override fun saveTokens(hostId: String, tokens: NativeAuthTokens) {}
            override fun getTokens(hostId: String): NativeAuthTokens? = null
            override fun clearTokens(hostId: String) {}
            override fun getAllHostIds(): Set<String> = emptySet()
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"version":"1.0.0","auth_required":true}""")
        )

        val host = HermesHost(
            id = HermesHostId("test-corrupted-host"),
            displayName = "Corrupted Host",
            baseUrl = "http://${server.hostName}:${server.port}",
            allowCleartext = true
        )

        val runtime = HermesHostRuntime(
            initialHost = host,
            restClient = HermesRestClient(client = OkHttpClient()),
            tokenVault = corruptedVault
        )

        val result = runtime.connect()

        assertTrue("Expected failure when authentication is required and vault has no valid tokens", result.isFailure)
        assertEquals("Host must transition to AUTH_REQUIRED on token absence/corruption", HostStatus.AUTH_REQUIRED, runtime.status.value)

        runtime.close()
    }
}
