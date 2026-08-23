package app.hermes.mobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.HermesApproval
import app.hermes.mobile.core.model.HermesClarifyRequest
import app.hermes.mobile.core.model.HermesMessage
import app.hermes.mobile.core.model.SessionInfo
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.repository.HermesGatewayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val error: String? = null,
    val inputText: String = ""
)

class ChatViewModel(
    private val gatewayRepo: HermesGatewayRepository
) : ViewModel() {

    val messages: StateFlow<List<HermesMessage>> = gatewayRepo.messages
    val activeApprovals: StateFlow<List<HermesApproval>> = gatewayRepo.activeApprovals
    val activeClarify: StateFlow<HermesClarifyRequest?> = gatewayRepo.activeClarify
    val sessionInfo: StateFlow<SessionInfo?> = gatewayRepo.sessionInfo
    val isExecuting: StateFlow<Boolean> = gatewayRepo.isExecuting
    val connectionState: StateFlow<ConnectionState> = gatewayRepo.connectionState
    val activeConnection = gatewayRepo.activeConnection
    val activeDurableId: StateFlow<DurableSessionId?> = gatewayRepo.activeDurableId

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun submitPrompt() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        _uiState.value = _uiState.value.copy(inputText = "", error = null)
        viewModelScope.launch {
            try {
                gatewayRepo.sendUserPrompt(text)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to submit prompt"
                )
            }
        }
    }

    fun interruptSession() {
        viewModelScope.launch {
            gatewayRepo.interruptSession()
        }
    }

    fun respondApproval(requestId: String, choice: String, all: Boolean = false) {
        viewModelScope.launch {
            try {
                gatewayRepo.respondApproval(requestId, choice, all)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to respond to approval"
                )
            }
        }
    }

    fun respondClarify(request: HermesClarifyRequest, answer: String) {
        viewModelScope.launch {
            try {
                when (request.promptType) {
                    ClarifyType.CLARIFY -> gatewayRepo.respondClarify(request.requestId, answer, request.questionId)
                    ClarifyType.SUDO -> gatewayRepo.respondSudo(request.requestId, answer)
                    ClarifyType.SECRET -> gatewayRepo.respondSecret(request.requestId, answer)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to respond to clarification"
                )
            }
        }
    }

    fun dismissClarify() {
        // Can be cancelled or handled
    }
}
