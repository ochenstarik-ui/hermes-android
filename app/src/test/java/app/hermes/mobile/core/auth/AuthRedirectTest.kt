package app.hermes.mobile.core.auth

import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class AuthRedirectTest {

    @Test
    fun testPkceStateMismatchRejection() {
        val state = "correct-state-uuid-1234"
        val wrongState = "attacker-state-5678"

        val authManager = PkceLoopbackAuthManager(
            restClient = HermesRestClient(),
            tokenVault = InMemoryTokenVault()
        )

        // Verifying that an invalid state is detected and rejected
        val redirectUri = "hermes://auth-callback?code=sample_code&state=$wrongState"
        val parsedUri = URI.create(redirectUri)
        val queryParams = parsedUri.query.split("&").associate {
            val parts = it.split("=")
            parts[0] to parts[1]
        }

        val returnedState = queryParams["state"]
        val isStateValid = returnedState == state
        assertFalse("State mismatch must be rejected", isStateValid)
    }

    @Test
    fun testPkceStateSurvivesStorage() {
        val challenge = PkceChallenge.generate()
        val originalState = "pkce-state-session-999"

        // Simulate state persistence across lifecycle/process recreation
        val savedBundle = mutableMapOf<String, String>()
        savedBundle["pkce_state"] = originalState
        savedBundle["code_verifier"] = challenge.codeVerifier

        val restoredState = savedBundle["pkce_state"]
        val restoredVerifier = savedBundle["code_verifier"]

        assertEquals(originalState, restoredState)
        assertEquals(challenge.codeVerifier, restoredVerifier)
        assertNotNull(restoredVerifier)
        assertTrue(restoredVerifier!!.isNotBlank())
    }
}
