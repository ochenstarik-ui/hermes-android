package app.hermes.mobile.feature.chat

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostAttributedApproval
import app.hermes.mobile.core.model.HostAttributedClarify
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.InMemoryTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var hostDao: FakeHostDao
    private lateinit var sessionDao: FakeUnifiedSessionDao
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var connectionManager: HermesConnectionManager
    private lateinit var repository: UnifiedSessionRepository
    private lateinit var viewModel: ChatViewModel

    private val sessionId = UnifiedSessionId("test-session-123")
    private val hostId = HermesHostId("host-main")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        hostDao = FakeHostDao()
        sessionDao = FakeUnifiedSessionDao()
        tokenVault = InMemoryTokenVault()
        connectionManager = HermesConnectionManager(hostDao, tokenVault, scope = CoroutineScope(testDispatcher))
        repository = UnifiedSessionRepository(connectionManager, sessionDao, scope = CoroutineScope(testDispatcher))

        val host = HermesHost(id = hostId, displayName = "Main Host", baseUrl = "http://localhost:9119")
        runTest(testDispatcher) {
            connectionManager.addHost(host)
            repository.createUnifiedSession("Test Chat", hostId)
            testScheduler.advanceUntilIdle()
        }

        viewModel = ChatViewModel(repository, connectionManager, sessionId)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testUpdateInputText() {
        assertEquals("", viewModel.uiState.value.inputText)
        viewModel.updateInputText("Hello agent")
        assertEquals("Hello agent", viewModel.uiState.value.inputText)
    }

    @Test
    fun testSubmitEmptyPromptDoesNothing() {
        viewModel.updateInputText("   ")
        viewModel.submitPrompt()
        assertEquals("   ", viewModel.uiState.value.inputText)
    }

    @Test
    fun testHostDropdownToggle() {
        assertEquals(false, viewModel.uiState.value.activeHostDropdownExpanded)
        viewModel.setHostDropdownExpanded(true)
        assertEquals(true, viewModel.uiState.value.activeHostDropdownExpanded)
    }

    @Test
    fun testSwitchActiveHost() = runTest(testDispatcher) {
        val host2Id = HermesHostId("host-secondary")
        val host2 = HermesHost(id = host2Id, displayName = "Secondary Host", baseUrl = "http://second:9119")
        connectionManager.addHost(host2)
        testScheduler.advanceUntilIdle()

        viewModel.switchActiveHost(host2Id)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.activeHostDropdownExpanded)
    }

    @Test
    fun testInterruptHandling() = runTest(testDispatcher) {
        viewModel.interruptSession()
        // No crash, handled gracefully
    }
}
