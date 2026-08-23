package app.hermes.mobile.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceChallengeTest {

    @Test
    fun testRfc7636AppendixBTestVector() {
        // RFC 7636 Appendix B test vector:
        val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val computedChallenge = PkceChallenge.computeChallenge(codeVerifier)
        assertEquals(expectedChallenge, computedChallenge)
    }

    @Test
    fun testGenerateProducesValidLengthAndCharset() {
        val challenge = PkceChallenge.generate(64)
        assertNotNull(challenge.codeVerifier)
        assertNotNull(challenge.codeChallenge)
        assertEquals(64, challenge.codeVerifier.length)
        assertEquals("S256", challenge.method)

        // Verifier must only contain unreserved characters: [A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~"
        val validRegex = Regex("^[A-Za-z0-9\\-._~]+$")
        assertTrue(challenge.codeVerifier.matches(validRegex))
    }
}
