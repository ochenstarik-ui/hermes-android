package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.BindingState
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.RuntimeSessionId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EmptyIdRejectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository

    private val hostId = HermesHostId("test-host-reject")

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
                HermesHostRuntime(
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
    fun testEmptyMessageIdDoesNotCreateMessageInRepository() = runTest(testDispatcher) {
        val host = HermesHost(id = hostId, displayName = "Host Reject", baseUrl = "http://host-reject:9119")
        connectionManager.addHost(host)
        testScheduler.advanceUntilIdle()

        val session = repository.createUnifiedSession(title = "Empty ID Test", initialHostId = hostId)
        testScheduler.advanceUntilIdle()

        val runtimeSessionId = "rt_empty_test"
        repository.registerRuntimeBinding(session.id, hostId, RuntimeSessionId(runtimeSessionId))
        sessionDao.insertOrUpdateBinding(
            HostBindingEntity(
                sessionId = session.id.value,
                hostId = hostId.value,
                durableSessionId = "dur_empty_test",
                runtimeSessionId = runtimeSessionId,
                state = BindingState.RUNNING.name
            )
        )
        testScheduler.advanceUntilIdle()

        val runtime = connectionManager.getRuntime(hostId)

        // 1. MessageStart without message_id (or empty message_id)
        val msgStartWithoutId = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.start")
                put("session_id", runtimeSessionId)
                put("payload", buildJsonObject {
                    put("role", "assistant")
                })
            })
        }
        runtime?.gatewayClient?.handleIncomingMessage(msgStartWithoutId.toString())
        testScheduler.advanceUntilIdle()

        // 2. MessageDelta without message_id
        val msgDeltaWithoutId = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.delta")
                put("session_id", runtimeSessionId)
                put("payload", buildJsonObject {
                    put("delta", "Corrupted delta without ID")
                })
            })
        }
        runtime?.gatewayClient?.handleIncomingMessage(msgDeltaWithoutId.toString())
        testScheduler.advanceUntilIdle()

        // 3. MessageComplete without message_id
        val msgCompleteWithoutId = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", "message.complete")
                put("session_id", runtimeSessionId)
                put("payload", buildJsonObject {
                    put("content", "Corrupted complete without ID")
                })
            })
        }
        runtime?.gatewayClient?.handleIncomingMessage(msgCompleteWithoutId.toString())
        testScheduler.advanceUntilIdle()

        val messages = repository.getSessionMessages(session.id).value
        assertTrue("No messages should be created from events missing message_id", messages.isEmpty())
        val dbMessages = sessionDao.getMessagesForSession(session.id.value)
        assertTrue("No entities should be persisted in DB from events missing message_id", dbMessages.isEmpty())
    }
}
