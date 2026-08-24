package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.model.NativeAuthTokens
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpErrorTypingTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenVault: InMemoryTokenVault

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenVault = InMemoryTokenVault()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun testHttp500With401SubstringInMessageOrBodyDoesNotClearTokens() {
        runBlocking {
            val hostId = "test-host-typing"
            val initialTokens = NativeAuthTokens(
                accessToken = "valid_access_token_123",
                refreshToken = "valid_refresh_token_456",
                expiresAt = System.currentTimeMillis() / 1000 + 3600 // Valid not expiring
            )
            tokenVault.saveTokens(hostId, initialTokens)

            // 1. First request is /api/status -> auth_required = true
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"version":"1.0.0","auth_required":true}""")
            )

            // 2. Second request is /api/auth/ws-ticket -> returns HTTP 500, but message/body mentions "401"
            server.enqueue(
                MockResponse()
                    .setStatus("HTTP/1.1 500 Internal error code 401 in proxy")
                    .setBody("""{"error":"Internal Server Error","details":"Upstream node 401 unreachable"}""")
            )

            val host = HermesHost(
                id = HermesHostId(hostId),
                displayName = "Test Typing Host",
                baseUrl = "http://${server.hostName}:${server.port}",
                allowCleartext = true
            )

            val runtime = HermesHostRuntime(
                initialHost = host,
                restClient = HermesRestClient(client = OkHttpClient()),
                tokenVault = tokenVault
            )

            val result = runtime.connect()

            // Result should be a failure due to HTTP 500
            assertTrue("Expected failure on HTTP 500 response", result.isFailure)

            // Verification: Token vault must NOT be cleared when error is HTTP 500 (even with "401" in message/body)
            val tokensAfter = tokenVault.getTokens(hostId)
            assertNotNull("Tokens must NOT be cleared on HTTP 500 even if message/body contains '401'", tokensAfter)
            assertNotEquals("Runtime status must not be AUTH_EXPIRED on HTTP 500 error", HostStatus.AUTH_EXPIRED, runtime.status.value)

            runtime.close()
        }
    }
}
