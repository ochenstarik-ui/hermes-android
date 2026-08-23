package app.hermes.mobile.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.repository.HermesGatewayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val error: String? = null
)

class SessionsViewModel(
    private val gatewayRepo: HermesGatewayRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = _uiState.asStateFlow()

    val activeConnection = gatewayRepo.activeConnection
    val connectionState = gatewayRepo.connectionState

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val list = gatewayRepo.listSessions()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sessions = list.sortedByDescending { it.startedAt },
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Failed to load sessions"
                )
            }
        }
    }

    fun createNewSession(onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val res = gatewayRepo.startNewSession()
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess(res.durableId.value)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Failed to create session"
                )
            }
        }
    }

    fun resumeSession(durableId: DurableSessionId, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val res = gatewayRepo.openSession(durableId)
                _uiState.value = _uiState.value.copy(isLoading = false)
                onSuccess(res.durableId.value)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Failed to resume session"
                )
            }
        }
    }
}
