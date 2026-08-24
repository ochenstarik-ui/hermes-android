package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class AppLifecycleTest {

    @Test
    fun testSingleHostIdCorrespondsToAtMostOneLiveRuntime() = runTest {
        val hostDao = FakeHostDao()
        val tokenVault = InMemoryTokenVault()
        val restClient = HermesRestClient()
        
        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            restClient = restClient,
            scope = backgroundScope,
            runtimeFactory = { parentScope, host ->
                val childScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
                app.hermes.mobile.core.runtime.HermesHostRuntime(
                    initialHost = host,
                    restClient = restClient,
                    gatewayClient = app.hermes.mobile.core.network.JsonRpcGatewayClient(scope = childScope),
                    tokenVault = tokenVault,
                    scope = childScope
                )
            }
        )
        
        val host = HermesHost(
            id = HermesHostId("h1"),
            displayName = "Host 1",
            baseUrl = "http://host1.com",
            allowCleartext = true,
            enabled = true,
            lastSeenAt = 0L,
            lastKnownStatus = HostStatus.OFFLINE
        )
        
        val runtime1 = connectionManager.getOrCreateRuntime(host)
        val runtime2 = connectionManager.getOrCreateRuntime(host)
        val runtime3 = connectionManager.getRuntime(host.id)
        
        assertSame(runtime1, runtime2)
        assertSame(runtime1, runtime3)
    }
}
