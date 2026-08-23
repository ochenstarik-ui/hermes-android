package app.hermes.mobile.core.security

import app.hermes.mobile.core.model.NativeAuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostScopedTokenManagementTest {

    private lateinit var tokenVault: InMemoryTokenVault

    @Before
    fun setUp() {
        tokenVault = InMemoryTokenVault()
    }

    @Test
    fun testHostIsolation() {
        val host1Id = "host-cloud-1"
        val host2Id = "host-local-lan"

        val tokens1 = NativeAuthTokens(
            accessToken = "access_token_cloud_123",
            refreshToken = "refresh_token_cloud_456",
            provider = "github",
            expiresAt = 1800000000L
        )

        val tokens2 = NativeAuthTokens(
            accessToken = "access_token_lan_789",
            refreshToken = "refresh_token_lan_012",
            provider = "local",
            expiresAt = 1900000000L
        )

        tokenVault.saveTokens(host1Id, tokens1)
        tokenVault.saveTokens(host2Id, tokens2)

        val retrieved1 = tokenVault.getTokens(host1Id)
        val retrieved2 = tokenVault.getTokens(host2Id)

        assertNotNull(retrieved1)
        assertNotNull(retrieved2)

        assertEquals("access_token_cloud_123", retrieved1?.accessToken)
        assertEquals("access_token_lan_789", retrieved2?.accessToken)

        // Clear host 1
        tokenVault.clearTokens(host1Id)
        assertNull(tokenVault.getTokens(host1Id))
        assertNotNull(tokenVault.getTokens(host2Id))

        assertEquals(setOf(host2Id), tokenVault.getAllHostIds())
    }
}
