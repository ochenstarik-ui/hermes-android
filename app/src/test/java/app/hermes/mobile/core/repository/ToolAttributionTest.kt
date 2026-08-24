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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that when 2 hosts stream concurrently into the same session with tool events
 * and thinking deltas, tools and thinking are strictly bound to their respective host and
 * explicit messageId, avoiding false attribution.
 */
class ToolAttributionTest {

    @Test
    fun testTwoConcurrentHostsAttributionAndThinkingIsolation() = runBlocking {
        val hostAId = HermesHostId("host-a")
        val hostBId = HermesHostId("host-b")

        val hostA = HermesHost(id = hostAId, displayName = "Host A", baseUrl = "http://host-a:9119")
        val hostB = HermesHost(id = hostBId, displayName = "Host B", baseUrl = "http://host-b:9119")

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

        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        delay(50)

        val session = repository.createUnifiedSession(title = "Dual Host Attribution Test", initialHostId = hostAId)
        val rtSessionA = "rt_session_host_a"
        val rtSessionB = "rt_session_host_b"

        repository.registerRuntimeBinding(session.id, hostAId, RuntimeSessionId(rtSessionA))
        repository.registerRuntimeBinding(session.id, hostBId, RuntimeSessionId(rtSessionB))

        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostAId.value,
                durableSessionId = "dur_a",
                runtimeSessionId = rtSessionA,
                state = BindingState.RUNNING.name
            )
        )
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostBId.value,
                durableSessionId = "dur_b",
                runtimeSessionId = rtSessionB,
                state = BindingState.RUNNING.name
            )
        )

        val runtimeA = connectionManager.getRuntime(hostAId)!!
        val runtimeB = connectionManager.getRuntime(hostBId)!!

        val msgAId = "msg_host_a_1"
        val msgBId = "msg_host_b_1"

        // 1. Host A starts message
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msgAId)
                    put("role", "assistant")
                })
            })
        }.toString())

        // 2. Host B starts message
        runtimeB.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionB)
                put("payload", buildJsonObject {
                    put("message_id", msgBId)
                    put("role", "assistant")
                })
            })
        }.toString())

        delay(30)

        // 3. Host A streams thinking delta for msgA
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "thinking.delta")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msgAId)
                    put("delta", "Plan on Host A")
                })
            })
        }.toString())

        // 4. Host B streams thinking delta for msgB
        runtimeB.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "thinking.delta")
                put("session_id", rtSessionB)
                put("payload", buildJsonObject {
                    put("message_id", msgBId)
                    put("delta", "Plan on Host B")
                })
            })
        }.toString())

        // 5. Host A starts tool
        val toolAId = "tool_host_a_1"
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.start")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("tool_id", toolAId)
                    put("name", "bash_executor")
                })
            })
        }.toString())

        // 6. Host B starts tool
        val toolBId = "tool_host_b_1"
        runtimeB.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.start")
                put("session_id", rtSessionB)
                put("payload", buildJsonObject {
                    put("tool_id", toolBId)
                    put("name", "file_editor")
                })
            })
        }.toString())

        delay(30)

        // 7. Update tool progress and completion for host A
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.complete")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("tool_id", toolAId)
                    put("result", "Output from Host A")
                    put("is_error", false)
                })
            })
        }.toString())

        // 8. Update tool progress and completion for host B
        runtimeB.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.complete")
                put("session_id", rtSessionB)
                put("payload", buildJsonObject {
                    put("tool_id", toolBId)
                    put("result", "Output from Host B")
                    put("is_error", false)
                })
            })
        }.toString())

        // 9. Complete messages
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.complete")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msgAId)
                    put("content", "Final content A")
                })
            })
        }.toString())

        runtimeB.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.complete")
                put("session_id", rtSessionB)
                put("payload", buildJsonObject {
                    put("message_id", msgBId)
                    put("content", "Final content B")
                })
            })
        }.toString())

        delay(50)

        val messages = repository.getSessionMessages(session.id).value
        val msgA = messages.find { it.id == msgAId }
        val msgB = messages.find { it.id == msgBId }

        assertNotNull("Message A must exist", msgA)
        assertNotNull("Message B must exist", msgB)

        assertEquals("Host A attribution", hostAId, msgA?.hostId)
        assertEquals("Host B attribution", hostBId, msgB?.hostId)

        assertEquals("Thinking for Message A", "Plan on Host A", msgA?.thinking)
        assertEquals("Thinking for Message B", "Plan on Host B", msgB?.thinking)

        assertEquals("Tools count for Message A", 1, msgA?.tools?.size)
        assertEquals("Tool ID for Message A", toolAId, msgA?.tools?.firstOrNull()?.id)
        assertEquals("Tool result for Message A", "Output from Host A", msgA?.tools?.firstOrNull()?.result)

        assertEquals("Tools count for Message B", 1, msgB?.tools?.size)
        assertEquals("Tool ID for Message B", toolBId, msgB?.tools?.firstOrNull()?.id)
        assertEquals("Tool result for Message B", "Output from Host B", msgB?.tools?.firstOrNull()?.result)
    }

    @Test
    fun testThinkingDeltaWithExplicitMessageIdDoesNotFallBackToLastAssistant() = runBlocking {
        val hostAId = HermesHostId("host-a")
        val hostA = HermesHost(id = hostAId, displayName = "Host A", baseUrl = "http://host-a:9119")

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

        connectionManager.addHost(hostA)
        delay(50)

        val session = repository.createUnifiedSession(title = "Message Targeting Test", initialHostId = hostAId)
        val rtSessionA = "rt_session_host_a"

        repository.registerRuntimeBinding(session.id, hostAId, RuntimeSessionId(rtSessionA))
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostAId.value,
                durableSessionId = "dur_a",
                runtimeSessionId = rtSessionA,
                state = BindingState.RUNNING.name
            )
        )

        val runtimeA = connectionManager.getRuntime(hostAId)!!

        val msg1Id = "msg_first_1"
        val msg2Id = "msg_second_2"

        // Host A starts first assistant message
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msg1Id)
                    put("role", "assistant")
                })
            })
        }.toString())

        // Host A starts second assistant message
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msg2Id)
                    put("role", "assistant")
                })
            })
        }.toString())

        delay(30)

        // Send thinking delta explicitly targeted at msg1Id
        runtimeA.gatewayClient.handleIncomingMessage(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "thinking.delta")
                put("session_id", rtSessionA)
                put("payload", buildJsonObject {
                    put("message_id", msg1Id)
                    put("delta", "Thinking targeted exclusively at msg1")
                })
            })
        }.toString())

        delay(30)

        val messages = repository.getSessionMessages(session.id).value
        val msg1 = messages.find { it.id == msg1Id }
        val msg2 = messages.find { it.id == msg2Id }

        assertNotNull("msg1 must exist", msg1)
        assertNotNull("msg2 must exist", msg2)

        // On base SHA: targetAssistant is lastOrNull { (it.id == msg1Id || it.role == ASSISTANT) }
        // Because msg2 has role == ASSISTANT and is last, it matches msg2!
        // So msg1 thinking will be null and msg2 thinking will have the text on base SHA!
        assertEquals("msg1 must receive thinking targeted at it", "Thinking targeted exclusively at msg1", msg1?.thinking)
        assertEquals("msg2 must NOT receive thinking intended for msg1", null, msg2?.thinking)
    }
}
