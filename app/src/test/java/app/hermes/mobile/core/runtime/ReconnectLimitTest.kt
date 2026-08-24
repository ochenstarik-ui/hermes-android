package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectLimitTest {

    @Test
    fun testMaxReconnectAttemptsStopsLoopAndResetsOnNetworkRestore() = runTest {
        val attempts = AtomicInteger(0)

        val failingRestClient = object : HermesRestClient() {
            override suspend fun getStatus(baseUrl: String, allowCleartext: Boolean): Result<HermesServerStatus> {
                attempts.incrementAndGet()
                return Result.failure(IOException("Simulated 500 server error"))
            }
        }

        val host = HermesHost(
            id = HermesHostId("host-limit"),
            displayName = "Limit Host",
            baseUrl = "http://mock-host:9119",
            allowCleartext = true,
            enabled = true,
            lastKnownStatus = HostStatus.OFFLINE
        )

        val runtime = HermesHostRuntime(
            initialHost = host,
            restClient = failingRestClient,
            tokenVault = InMemoryTokenVault(),
            scope = backgroundScope
        )

        // Initial connect fails due to simulated 500
        val initialRes = runtime.connect()
        assertTrue("Initial connect must fail", initialRes.isFailure)

        // Trigger automatic reconnect loop
        runtime.scheduleReconnect()

        // Advance virtual time through all backoff delays (1s, 2s, 4s, 8s, 16s, etc.)
        advanceTimeBy(60_000)
        advanceUntilIdle()

        // After max attempts (5), autoReconnect must stop and status must be ERROR
        assertTrue("Status must be ERROR after reaching max reconnect attempts",
            runtime.status.value == HostStatus.ERROR || runtime.status.value == HostStatus.OFFLINE
        )
        assertFalse("Auto reconnect must be stopped after max attempts", runtime.isAutoReconnectActive())
        assertTrue("Reconnect attempt count must be at least MAX_RECONNECT_ATTEMPTS (5)",
            runtime.getReconnectAttemptCount() >= HermesHostRuntime.MAX_RECONNECT_ATTEMPTS
        )

        // Now simulate network restore
        runtime.onNetworkRestored()

        // Verify attempts counter is reset to 0
        assertEquals("Reconnect attempts must be reset to 0 upon network restore", 0, runtime.getReconnectAttemptCount())
        assertTrue("Auto reconnect must be re-enabled on network restore", runtime.isAutoReconnectActive())

        runtime.close()
    }
}
