package app.hermes.mobile.core.auth

import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class RedirectUriTest {

    @Test
    fun testRedirectUriMatchesLoopbackContractAndNotCustomScheme() = runBlocking {
        val authManager = PkceLoopbackAuthManager(
            restClient = HermesRestClient(),
            tokenVault = InMemoryTokenVault()
        )

        val authUrlDeferred = CompletableDeferred<String>()
        val job = CoroutineScope(Dispatchers.IO).launch {
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
        job.cancel()

        assertNotNull("authUrl must be generated", authUrl)
        assertTrue("authUrl must start with host baseUrl", authUrl.startsWith("http://127.0.0.1:9119/auth/native/authorize"))

        val uri = URI(authUrl)
        val query = uri.rawQuery
        val queryParams = query.split("&").associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        }

        val redirectUri = queryParams["redirect_uri"]
        assertNotNull("redirect_uri parameter must be present", redirectUri)

        // Must match loopback http://127.0.0.1:<port>/callback
        assertTrue(
            "redirect_uri must be loopback http://127.0.0.1:<port>/callback, got: $redirectUri",
            redirectUri!!.matches(Regex("^http://127\\.0\\.0\\.1:\\d+/callback$"))
        )

        // Must NOT use dead hermes:// scheme
        assertFalse(
            "redirect_uri must NOT use custom scheme hermes://auth-callback",
            redirectUri.startsWith("hermes://")
        )

        // Verify other required PKCE parameters
        assertNotNull("code_challenge must be present", queryParams["code_challenge"])
        assertTrue("code_challenge must not be empty", queryParams["code_challenge"]!!.isNotEmpty())
        assertEquals("S256", queryParams["code_challenge_method"])
        assertNotNull("state must be present", queryParams["state"])
        assertTrue("state must not be empty", queryParams["state"]!!.isNotEmpty())
        assertEquals("github", queryParams["provider"])
    }
}
