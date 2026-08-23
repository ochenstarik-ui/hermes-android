package app.hermes.mobile.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val error: String? = null,
    val inputText: String = "",
    val activeHostDropdownExpanded: Boolean = false
)

class ChatViewModel(
    val sessionRepo: UnifiedSessionRepository,
    val connectionManager: HermesConnectionManager,
    private val sessionId: UnifiedSessionId
) : ViewModel() {

    val hosts: StateFlow<List<HermesHost>> = connectionManager.hosts
    val messages: StateFlow<List<UnifiedMessage>> = sessionRepo.getSessionMessages(sessionId)
    val isExecuting: StateFlow<Boolean> = sessionRepo.getSessionExecuting(sessionId)
    val activeApprovals: StateFlow<List<HostAttributedApproval>> = sessionRepo.activeApprovals
    val activeClarify: StateFlow<HostAttributedClarify?> = sessionRepo.activeClarify

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _currentSession = MutableStateFlow<UnifiedSession?>(null)
    val currentSession: StateFlow<UnifiedSession?> = _currentSession.asStateFlow()

    init {
        loadSession()
    }

    fun loadSession() {
        viewModelScope.launch {
            _currentSession.value = sessionRepo.getUnifiedSession(sessionId)
        }
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun setHostDropdownExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(activeHostDropdownExpanded = expanded)
    }

    fun switchActiveHost(targetHostId: HermesHostId) {
        viewModelScope.launch {
            try {
                sessionRepo.switchSessionActiveHost(sessionId, targetHostId)
                connectionManager.switchActiveHost(targetHostId)
                loadSession()
                _uiState.value = _uiState.value.copy(activeHostDropdownExpanded = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.localizedMessage ?: "Failed to switch host")
            }
        }
    }

    fun submitPrompt() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        _uiState.value = _uiState.value.copy(inputText = "", error = null)
        viewModelScope.launch {
            try {
                sessionRepo.sendPrompt(sessionId, text)
                loadSession()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to submit prompt"
                )
            }
        }
    }

    fun interruptSession() {
        viewModelScope.launch {
            sessionRepo.interruptSession(sessionId)
        }
    }

    fun respondApproval(hostId: HermesHostId, requestId: String, choice: String, all: Boolean = false) {
        viewModelScope.launch {
            try {
                sessionRepo.respondApproval(hostId, requestId, choice, all)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to respond to approval"
                )
            }
        }
    }

    fun respondClarify(attributed: HostAttributedClarify, answer: String) {
        viewModelScope.launch {
            try {
                val hostId = attributed.hostId
                val req = attributed.request
                when (req.promptType) {
                    ClarifyType.CLARIFY -> sessionRepo.respondClarify(hostId, req.requestId, answer, req.questionId)
                    ClarifyType.SUDO -> sessionRepo.respondSudo(hostId, req.requestId, answer)
                    ClarifyType.SECRET -> sessionRepo.respondSecret(hostId, req.requestId, answer)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.localizedMessage ?: "Failed to respond to clarification"
                )
            }
        }
    }
}
