package app.hermes.mobile.core.auth

import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class LoopbackCancellationTest {

    @Test
    fun testLoopbackAuthCancellationClosesSocketAndReleasesThread() = runTest {
        val authManager = PkceLoopbackAuthManager(
            restClient = HermesRestClient(),
            tokenVault = InMemoryTokenVault()
        )

        val authUrlDeferred = CompletableDeferred<String>()
        val testScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        val job = testScope.launch {
            authManager.startAuthFlow(
                context = null,
                connectionId = "test-host",
                baseUrl = "http://127.0.0.1:9119",
                allowCleartext = true,
                onAuthUrlReady = { url ->
                    authUrlDeferred.complete(url)
                }
            )
        }

        val authUrl = withTimeout(5000) { authUrlDeferred.await() }
        assertNotNull(authUrl)

        // Extract listening port
        val uri = URI(authUrl)
        val query = uri.rawQuery
        val queryParams = query.split("&").associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        }
        val redirectUri = queryParams["redirect_uri"]!!
        val port = URI(redirectUri).port
        assertTrue("Port must be valid positive int", port > 0)

        // Cancel the job — must complete promptly (within 2 seconds) and release socket
        withTimeout(2000) {
            job.cancelAndJoin()
        }

        // Verify socket is closed and refuses new connections
        val socketClosed = try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 500)
            s.close()
            false
        } catch (_: Exception) {
            true
        }
        assertTrue("Loopback ServerSocket must be closed after coroutine cancellation", socketClosed)
    }
}
