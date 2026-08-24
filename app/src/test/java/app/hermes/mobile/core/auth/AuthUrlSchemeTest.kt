package app.hermes.mobile.core.auth

import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUrlSchemeTest {

    @Test
    fun testHttpAuthUrlRejectedWhenCleartextDisallowed() {
        runBlocking {
            val authManager = PkceLoopbackAuthManager(
                restClient = HermesRestClient(),
                tokenVault = InMemoryTokenVault()
            )

            var authUrlReadyCalled = false
            val result = authManager.startAuthFlow(
                context = null,
                connectionId = "test-host",
                baseUrl = "http://insecure-host.lan:8080",
                allowCleartext = false,
                onAuthUrlReady = {
                    authUrlReadyCalled = true
                }
            )

            assertFalse("onAuthUrlReady must not be called when cleartext is disallowed for http URL", authUrlReadyCalled)
            assertTrue("Expected failure for http authUrl when allowCleartext is false", result.isFailure)
            val ex = result.exceptionOrNull()
            assertTrue("Expected SecurityException but got $ex", ex is SecurityException)
        }
    }
}
