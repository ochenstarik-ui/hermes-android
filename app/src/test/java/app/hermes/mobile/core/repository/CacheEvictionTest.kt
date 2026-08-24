package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.HostBindingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies that opening, streaming in, and closing/unsubscribing from 50 sessions
 * does not leak memory in repository caches (sessionMessagesState, hostExecutingState,
 * sessionExecutingState, sessionHostMutexes, toolIdToMessageId).
 */
class CacheEvictionTest {

    @Suppress("UNCHECKED_CAST")
    private fun getInternalMapSize(repository: UnifiedSessionRepository, fieldName: String): Int {
        return try {
            val field = UnifiedSessionRepository::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            val map = field.get(repository) as? Map<*, *>
            map?.size ?: 0
        } catch (_: NoSuchFieldException) {
            0
        }
    }

    @Test
    fun test50SessionsCycleEvictsAndBoundsMemoryCaches() = runBlocking {
        val hostId = HermesHostId("host-cache-1")
        val host = HermesHost(id = hostId, displayName = "Cache Host", baseUrl = "http://cache-host:9119")

        val hostDao = FakeHostDao()
        val sessionDao = FakeUnifiedSessionDao()
        val tokenVault = InMemoryTokenVault()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = scope,
            runtimeFactory = { parentScope, h ->
                val childScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Default)
                HermesHostRuntime(
                    initialHost = h,
                    gatewayClient = JsonRpcGatewayClient(scope = childScope),
                    tokenVault = tokenVault,
                    scope = childScope
                )
            }
        )

        val repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = scope
        )

        connectionManager.addHost(host)
        delay(50)
        val runtime = connectionManager.getRuntime(hostId)!!

        val sessionCount = 50

        // Cycle through 50 sessions
        for (i in 1..sessionCount) {
            val session = repository.createUnifiedSession(title = "Session $i", initialHostId = hostId)
            val rtSessionId = "rt_session_$i"
            val msgId = "msg_$i"

            repository.registerRuntimeBinding(session.id, hostId, RuntimeSessionId(rtSessionId))
            sessionDao.insertOrUpdateBinding(
                HostBindingEntity(
                    sessionId = session.id.value,
                    hostId = hostId.value,
                    durableSessionId = "dur_$i",
                    runtimeSessionId = rtSessionId,
                    state = BindingState.RUNNING.name
                )
            )

            // Simulate subscription to session flows
            val messagesFlow = repository.getSessionMessages(session.id)
            val execFlow = repository.getSessionExecuting(session.id)
            val hostExecFlow = repository.getHostExecuting(session.id, hostId)

            val job = launch {
                messagesFlow.collect {}
            }

            // Stream a message with tool
            val startJson = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.start")
                    put("session_id", rtSessionId)
                    put("payload", buildJsonObject {
                        put("message_id", msgId)
                        put("role", "assistant")
                    })
                })
            }.toString()
            runtime.gatewayClient.handleIncomingMessage(startJson)

            val toolStartJson = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "tool.start")
                    put("session_id", rtSessionId)
                    put("payload", buildJsonObject {
                        put("tool_id", "tool_$i")
                        put("name", "test_tool")
                    })
                })
            }.toString()
            runtime.gatewayClient.handleIncomingMessage(toolStartJson)

            val completeJson = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.complete")
                    put("session_id", rtSessionId)
                    put("payload", buildJsonObject {
                        put("message_id", msgId)
                        put("content", "Done $i")
                    })
                })
            }.toString()
            runtime.gatewayClient.handleIncomingMessage(completeJson)
            delay(20)

            // Unsubscribe
            job.cancel()

            // Release session explicitly
            repository.releaseSession(session.id)
        }

        delay(100)

        val messagesCacheSize = getInternalMapSize(repository, "sessionMessagesState")
        val sessionExecSize = getInternalMapSize(repository, "sessionExecutingState")
        val hostExecSize = getInternalMapSize(repository, "hostExecutingState")
        val mutexesSize = getInternalMapSize(repository, "sessionHostMutexes")
        val toolMapSize = getInternalMapSize(repository, "toolToMessageMap").coerceAtLeast(
            getInternalMapSize(repository, "toolIdToMessageId")
        )

        println("BASELINE MEASUREMENT [Cache sizes after 50 sessions]: messagesCache=$messagesCacheSize, sessionExec=$sessionExecSize, hostExec=$hostExecSize, mutexes=$mutexesSize, toolMap=$toolMapSize")

        // Assert caches are pruned / bounded (bounded to at most small LRU size e.g. <= 5 or 0 when released)
        assertTrue("sessionMessagesState ($messagesCacheSize) must be bounded <= 5", messagesCacheSize <= 5)
        assertTrue("sessionExecutingState ($sessionExecSize) must be bounded <= 5", sessionExecSize <= 5)
        assertTrue("hostExecutingState ($hostExecSize) must be bounded <= 5", hostExecSize <= 5)
        assertTrue("sessionHostMutexes ($mutexesSize) must be bounded <= 5", mutexesSize <= 5)
        assertTrue("toolToMessageMap ($toolMapSize) must be pruned upon completion", toolMapSize <= 5)
    }
}
