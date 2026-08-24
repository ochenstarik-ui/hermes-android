package app.hermes.mobile.feature.native_sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.SessionSummary
import app.hermes.mobile.core.runtime.HermesConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NativeSessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionSummary> = emptyList(),
    val error: String? = null
)

class NativeSessionsViewModel(
    val connectionManager: HermesConnectionManager,
    val hostId: HermesHostId
) : ViewModel() {

    val host: HermesHost? get() = connectionManager.hosts.value.find { it.id == hostId }

    private val _uiState = MutableStateFlow(NativeSessionsUiState())
    val uiState: StateFlow<NativeSessionsUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val runtime = connectionManager.getRuntime(hostId)
                if (runtime == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Host not found")
                    return@launch
                }
                val list = runtime.gatewayClient.listSessions()
                _uiState.value = _uiState.value.copy(isLoading = false, sessions = list)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Failed to load native sessions"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
