package app.hermes.mobile.feature.unified_sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.UnifiedSession
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UnifiedSessionsUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)

class UnifiedSessionsViewModel(
    val sessionRepo: UnifiedSessionRepository,
    val connectionManager: HermesConnectionManager
) : ViewModel() {

    val sessions: StateFlow<List<UnifiedSession>> = sessionRepo.sessions
    val hosts: StateFlow<List<HermesHost>> = connectionManager.hosts
    val activeHostId: StateFlow<HermesHostId?> = connectionManager.activeHostId

    private val _uiState = MutableStateFlow(UnifiedSessionsUiState())
    val uiState: StateFlow<UnifiedSessionsUiState> = _uiState.asStateFlow()

    fun createNewSession(
        title: String = "New Session",
        initialHostId: HermesHostId? = null,
        onCreated: (UnifiedSessionId) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val session = sessionRepo.createUnifiedSession(title, initialHostId)
                onCreated(session.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.localizedMessage ?: "Failed to create session")
            }
        }
    }

    fun deleteSession(sessionId: UnifiedSessionId) {
        viewModelScope.launch {
            try {
                sessionRepo.deleteUnifiedSession(sessionId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.localizedMessage ?: "Failed to delete session")
            }
        }
    }
}
