package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.HostBindingEntity
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors

class ConcurrentTimelineTest {

    @Test
    fun test200ConcurrentMessageInsertionsFromTwoHosts() = runBlocking {
        val host1Id = HermesHostId("host-concurrent-1")
        val host2Id = HermesHostId("host-concurrent-2")
        val host1 = HermesHost(id = host1Id, displayName = "Host 1", baseUrl = "http://host1:9119")
        val host2 = HermesHost(id = host2Id, displayName = "Host 2", baseUrl = "http://host2:9119")

        val hostDao = FakeHostDao()
        val sessionDao = FakeUnifiedSessionDao()
        val tokenVault = InMemoryTokenVault()

        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = repoScope,
            runtimeFactory = { parentScope, host ->
                val childScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
                HermesHostRuntime(
                    initialHost = host,
                    restClient = app.hermes.mobile.core.network.HermesRestClient(),
                    gatewayClient = JsonRpcGatewayClient(scope = childScope),
                    tokenVault = tokenVault,
                    scope = childScope
                )
            }
        )

        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = repoScope
        )

        connectionManager.addHost(host1)
        connectionManager.addHost(host2)
        delay(100)

        val session = repository.createUnifiedSession(title = "Concurrent Timeline Test", initialHostId = host1Id)
        delay(100)

        val rt1 = "rt_conc_1"
        val rt2 = "rt_conc_2"
        repository.registerRuntimeBinding(session.id, host1Id, RuntimeSessionId(rt1))
        repository.registerRuntimeBinding(session.id, host2Id, RuntimeSessionId(rt2))

        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(sessionId = session.id.value, hostId = host1Id.value, durableSessionId = "dur1", runtimeSessionId = rt1, state = BindingState.RUNNING.name)
        )
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(sessionId = session.id.value, hostId = host2Id.value, durableSessionId = "dur2", runtimeSessionId = rt2, state = BindingState.RUNNING.name)
        )
        delay(100)

        val runtime1 = connectionManager.getRuntime(host1Id)!!
        val runtime2 = connectionManager.getRuntime(host2Id)!!

        val totalMessages = 200
        val half = totalMessages / 2

        // Dispatch 200 message start events concurrently from 2 hosts using multi-threaded dispatcher
        val threadPool = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        val jobs = mutableListOf<Job>()

        for (i in 0 until half) {
            jobs += CoroutineScope(threadPool).launch {
                val msgId = "host1-msg-$i"
                val json = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "event")
                    put("params", buildJsonObject {
                        put("type", "message.start")
                        put("session_id", rt1)
                        put("payload", buildJsonObject {
                            put("message_id", msgId)
                            put("role", "assistant")
                        })
                    })
                }.toString()
                runtime1.gatewayClient.handleIncomingMessage(json)
            }
        }

        for (i in 0 until half) {
            jobs += CoroutineScope(threadPool).launch {
                val msgId = "host2-msg-$i"
                val json = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "event")
                    put("params", buildJsonObject {
                        put("type", "message.start")
                        put("session_id", rt2)
                        put("payload", buildJsonObject {
                            put("message_id", msgId)
                            put("role", "assistant")
                        })
                    })
                }.toString()
                runtime2.gatewayClient.handleIncomingMessage(json)
            }
        }

        jobs.joinAll()
        delay(1000)

        val messagesInFlow = repository.getSessionMessages(session.id).value
        assertEquals("Timeline in memory must contain exactly 200 messages without lost updates", totalMessages, messagesInFlow.size)

        threadPool.close()
        runtime1.close()
        runtime2.close()
        repoScope.cancel()
    }
}
