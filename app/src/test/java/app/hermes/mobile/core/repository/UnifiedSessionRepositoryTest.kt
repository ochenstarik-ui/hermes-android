package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.HostBindingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedSessionRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository

    private val host1Id = HermesHostId("host-a")
    private val host2Id = HermesHostId("host-b")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()

        connectionManager = HermesConnectionManager(
            hostDao = hostDao,
            tokenVault = tokenVault,
            scope = CoroutineScope(testDispatcher),
            runtimeFactory = { parentScope, host ->
                val childScope = CoroutineScope(kotlinx.coroutines.SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + testDispatcher)
                app.hermes.mobile.core.runtime.HermesHostRuntime(
                    initialHost = host,
                    restClient = app.hermes.mobile.core.network.HermesRestClient(),
                    gatewayClient = app.hermes.mobile.core.network.JsonRpcGatewayClient(scope = childScope),
                    tokenVault = tokenVault,
                    scope = childScope
                )
            }
        )

        repository = UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = sessionDao,
            scope = CoroutineScope(testDispatcher)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCreateAndSwitchUnifiedSession() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Host A", baseUrl = "http://host-a:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Host B", baseUrl = "http://host-b:9119")

        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession(title = "Multi-Host Project", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        assertEquals(host1Id, session.activeHostId)
        assertEquals("Multi-Host Project", session.title)

        // Switch active host to Host B
        repository.switchSessionActiveHost(session.id, host2Id)
        testScheduler.advanceUntilIdle()

        val updated = repository.getUnifiedSession(session.id)
        assertEquals(host2Id, updated?.activeHostId)
    }

    @Test
    fun testStreamingEventAttribution() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Host A", baseUrl = "http://host-a:9119")
        connectionManager.addHost(hostA)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession(title = "Streaming Test", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        repository.registerRuntimeBinding(session.id, host1Id, RuntimeSessionId("rt_stream_1"))

        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_stream_1",
                runtimeSessionId = "rt_stream_1",
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        assertNotNull(runtimeA)

        // Stream start event
        val msgStart = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", "rt_stream_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_stream_1")
                    put("role", "assistant")
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(msgStart.toString())
        testScheduler.advanceUntilIdle()

        // Stream delta 1
        val msgDelta1 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "rt_stream_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_stream_1")
                    put("delta", "Hello ")
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(msgDelta1.toString())
        testScheduler.advanceUntilIdle()

        // Stream delta 2
        val msgDelta2 = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", "rt_stream_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_stream_1")
                    put("delta", "from Multi-Hermes!")
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(msgDelta2.toString())
        testScheduler.advanceUntilIdle()

        // Stream complete
        val msgComplete = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.complete")
                put("session_id", "rt_stream_1")
                put("payload", buildJsonObject {
                    put("message_id", "msg_stream_1")
                    put("content", "Hello from Multi-Hermes!")
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(msgComplete.toString())
        testScheduler.advanceUntilIdle()

        val messages = repository.getSessionMessages(session.id).value
        assertTrue(messages.any { it.id == "msg_stream_1" })
        val streamMsg = messages.find { it.id == "msg_stream_1" }
        assertEquals("Hello from Multi-Hermes!", streamMsg?.content)
        assertEquals(host1Id, streamMsg?.hostId)
        assertFalse(streamMsg?.isStreaming ?: true)
    }

    @Test
    fun testBackgroundHostExecutionEventHandling() = runTest(testDispatcher) {
        val hostA = HermesHost(id = host1Id, displayName = "Host A", baseUrl = "http://host-a:9119")
        val hostB = HermesHost(id = host2Id, displayName = "Host B", baseUrl = "http://host-b:9119")
        connectionManager.addHost(hostA)
        connectionManager.addHost(hostB)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession(title = "Background Session", initialHostId = host1Id)
        testScheduler.advanceUntilIdle()

        repository.registerRuntimeBinding(session.id, host1Id, RuntimeSessionId("rt_bg_1"))

        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = host1Id.value,
                durableSessionId = "dur_bg_1",
                runtimeSessionId = "rt_bg_1",
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        val runtimeA = connectionManager.getRuntime(host1Id)
        val runtimeB = connectionManager.getRuntime(host2Id)

        // Host A starts long tool operation
        val toolStart = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.start")
                put("session_id", "rt_bg_1")
                put("payload", buildJsonObject {
                    put("tool_id", "tool_bg_1")
                    put("name", "heavy_build_task")
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(toolStart.toString())
        testScheduler.advanceUntilIdle()

        // User switches session active host to Host B
        repository.switchSessionActiveHost(session.id, host2Id)
        testScheduler.advanceUntilIdle()

        // Host A finishes tool in background
        val toolComplete = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "tool.complete")
                put("session_id", "rt_bg_1")
                put("payload", buildJsonObject {
                    put("tool_id", "tool_bg_1")
                    put("result", "Build successful in 42s")
                    put("is_error", false)
                })
            })
        }
        runtimeA?.gatewayClient?.handleIncomingMessage(toolComplete.toString())
        testScheduler.advanceUntilIdle()

        val messages = repository.getSessionMessages(session.id).value
        val msgWithTool = messages.find { it.tools.any { t -> t.id == "tool_bg_1" } }
        assertNotNull(msgWithTool)
        assertEquals("completed", msgWithTool?.tools?.first()?.status)
        assertEquals("Build successful in 42s", msgWithTool?.tools?.first()?.result)
        assertEquals(host1Id, msgWithTool?.hostId)
    }
}
