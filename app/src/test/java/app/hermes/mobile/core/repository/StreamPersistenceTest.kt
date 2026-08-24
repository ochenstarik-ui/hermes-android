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
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPersistenceTest {

    @Test
    fun test500StreamingDeltasBufferedAndPersistedByteForByte() = runBlocking {
        val hostId = HermesHostId("host-stream-1")
        val host = HermesHost(id = hostId, displayName = "Stream Host", baseUrl = "http://stream-host:9119")

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
        delay(100)

        val session = repository.createUnifiedSession(title = "Streaming Persistence Test", initialHostId = hostId)
        delay(100)

        val rtSessionId = "rt_stream_test_500"
        val messageId = "msg_stream_500"
        repository.registerRuntimeBinding(session.id, hostId, RuntimeSessionId(rtSessionId))
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostId.value,
                durableSessionId = "dur_stream_500",
                runtimeSessionId = rtSessionId,
                state = BindingState.RUNNING.name
            )
        )
        delay(100)

        val runtime = connectionManager.getRuntime(hostId)!!

        // Start message
        val startJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionId)
                put("payload", buildJsonObject {
                    put("message_id", messageId)
                    put("role", "assistant")
                })
            })
        }.toString()
        runtime.gatewayClient.handleIncomingMessage(startJson)

        val totalDeltas = 500
        val expectedBuilder = StringBuilder()

        for (i in 1..totalDeltas) {
            val chunk = "chunk-$i;"
            expectedBuilder.append(chunk)
            val deltaJson = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.delta")
                    put("session_id", rtSessionId)
                    put("payload", buildJsonObject {
                        put("message_id", messageId)
                        put("delta", chunk)
                    })
                })
            }.toString()
            runtime.gatewayClient.handleIncomingMessage(deltaJson)
        }

        // Complete message
        val completeJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.complete")
                put("session_id", rtSessionId)
                put("payload", buildJsonObject {
                    put("message_id", messageId)
                    put("content", expectedBuilder.toString())
                })
            })
        }.toString()
        runtime.gatewayClient.handleIncomingMessage(completeJson)

        // Wait for persistence to complete
        delay(1500)

        val expectedContent = expectedBuilder.toString()
        val inMemoryMessage = repository.getSessionMessages(session.id).value.find { it.id == messageId }
        val inDbMessages = sessionDao.getMessagesForSession(session.id.value)
        val inDbMessage = inDbMessages.find { it.id == messageId }

        assertEquals("In-memory content must match expected", expectedContent, inMemoryMessage?.content)
        assertEquals("DB content must match in-memory content byte-for-byte", expectedContent, inDbMessage?.content)

        runtime.close()
        scope.cancel()
    }

    @Test
    fun testMidStreamCutoffDoesNotCorruptOrExceedReceivedContent() = runBlocking {
        val hostId = HermesHostId("host-stream-cutoff")
        val host = HermesHost(id = hostId, displayName = "Cutoff Host", baseUrl = "http://cutoff-host:9119")

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
        delay(100)

        val session = repository.createUnifiedSession(title = "Cutoff Test", initialHostId = hostId)
        delay(100)

        val rtSessionId = "rt_cutoff"
        val messageId = "msg_cutoff"
        repository.registerRuntimeBinding(session.id, hostId, RuntimeSessionId(rtSessionId))
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostId.value,
                durableSessionId = "dur_cutoff",
                runtimeSessionId = rtSessionId,
                state = BindingState.RUNNING.name
            )
        )
        delay(100)

        val runtime = connectionManager.getRuntime(hostId)!!

        val startJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionId)
                put("payload", buildJsonObject {
                    put("message_id", messageId)
                    put("role", "assistant")
                })
            })
        }.toString()
        runtime.gatewayClient.handleIncomingMessage(startJson)

        val expectedBuilder = StringBuilder()
        for (i in 1..250) {
            val chunk = "chunk-$i;"
            expectedBuilder.append(chunk)
            val deltaJson = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "event")
                put("params", buildJsonObject {
                    put("type", "message.delta")
                    put("session_id", rtSessionId)
                    put("payload", buildJsonObject {
                        put("message_id", messageId)
                        put("delta", chunk)
                    })
                })
            }.toString()
            runtime.gatewayClient.handleIncomingMessage(deltaJson)
        }

        // Cutoff happens: error event or disconnect without message.complete
        val errorJson = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "session.error")
                put("session_id", rtSessionId)
                put("payload", buildJsonObject {
                    put("error", "Stream interrupted abruptly")
                })
            })
        }.toString()
        runtime.gatewayClient.handleIncomingMessage(errorJson)

        delay(2500)

        val expectedPrefix = expectedBuilder.toString()
        val inMemoryMessage = repository.getSessionMessages(session.id).value.find { it.id == messageId }
        val inDbMessage = sessionDao.getMessagesForSession(session.id.value).find { it.id == messageId }

        assertEquals("In-memory content matches received deltas", expectedPrefix, inMemoryMessage?.content)
        assertTrue("DB content should be prefix or equal to received", inDbMessage?.content?.let { expectedPrefix.startsWith(it) } ?: false)

        runtime.close()
        scope.cancel()
    }
}
