package app.hermes.mobile.feature.chat

import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.HermesApproval
import app.hermes.mobile.core.model.HermesMessage
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.RuntimeSessionId
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.repository.HermesGatewayRepository
import app.hermes.mobile.core.security.InMemoryTokenVault
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
    private lateinit var restClient: HermesRestClient
    private lateinit var gatewayClient: JsonRpcGatewayClient
    private lateinit var tokenVault: InMemoryTokenVault
    private lateinit var repository: HermesGatewayRepository
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        restClient = HermesRestClient()
        gatewayClient = JsonRpcGatewayClient()
        tokenVault = InMemoryTokenVault()
        repository = HermesGatewayRepository(restClient, gatewayClient, tokenVault)
        viewModel = ChatViewModel(repository)
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
    fun testMessageHandlingStateFlow() {
        val initialMessages = viewModel.messages.value
        assertEquals(0, initialMessages.size)
    }

    @Test
    fun testClarifyRequestHandling() = runTest(testDispatcher) {
        val clarifyReq = app.hermes.mobile.core.model.HermesClarifyRequest(
            requestId = "req_101",
            questionId = "q_param",
            question = "Which database?",
            promptType = app.hermes.mobile.core.model.ClarifyType.CLARIFY
        )
        viewModel.respondClarify(clarifyReq, "PostgreSQL")
        // No crash, handled gracefully when disconnected
    }

    @Test
    fun testApprovalHandling() = runTest(testDispatcher) {
        viewModel.respondApproval("req_app_1", "once", false)
        // Handled gracefully
    }

    @Test
    fun testInterruptHandling() = runTest(testDispatcher) {
        viewModel.interruptSession()
        // Handled gracefully
    }
}
