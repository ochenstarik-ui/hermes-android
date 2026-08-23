package app.hermes.mobile.feature.connections

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.model.HermesConnection
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.repository.ConnectionRepository
import app.hermes.mobile.core.repository.HermesGatewayRepository
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val isTesting: Boolean = false,
    val testStatus: HermesServerStatus? = null,
    val testError: String? = null,
    val isAuthenticating: Boolean = false,
    val authError: String? = null
)

class ConnectionsViewModel(
    private val connectionRepo: ConnectionRepository,
    private val gatewayRepo: HermesGatewayRepository,
    private val tokenVault: TokenVault,
    private val pkceAuthManager: PkceLoopbackAuthManager
) : ViewModel() {

    val connections: StateFlow<List<HermesConnection>> = connectionRepo.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeConnection = gatewayRepo.activeConnection
    val connectionState = gatewayRepo.connectionState

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    fun isConnectionAuthenticated(connectionId: String): Boolean {
        return tokenVault.getTokens(connectionId) != null
    }

    fun saveConnection(name: String, baseUrl: String, allowCleartext: Boolean) {
        viewModelScope.launch {
            val connection = HermesConnection(
                name = name.ifBlank { baseUrl },
                baseUrl = baseUrl.trim(),
                allowCleartext = allowCleartext
            )
            connectionRepo.saveConnection(connection)
        }
    }

    fun removeConnection(connectionId: String) {
        viewModelScope.launch {
            tokenVault.clearTokens(connectionId)
            connectionRepo.removeConnection(connectionId)
        }
    }

    fun testConnection(baseUrl: String, allowCleartext: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testError = null, testStatus = null)
            val result = gatewayRepo.restClient.getStatus(baseUrl, allowCleartext)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testStatus = result.getOrNull(),
                    testError = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testError = result.exceptionOrNull()?.localizedMessage ?: "Connection failed"
                )
            }
        }
    }

    fun startSignIn(context: Context, connection: HermesConnection, provider: String = "github", onComplete: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isAuthenticating = true, authError = null)
            val result = pkceAuthManager.startAuthFlow(
                context = context,
                connectionId = connection.id,
                baseUrl = connection.baseUrl,
                provider = provider,
                allowCleartext = connection.allowCleartext
            )
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isAuthenticating = false, authError = null)
                onComplete()
            } else {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    authError = result.exceptionOrNull()?.localizedMessage ?: "Authentication failed"
                )
            }
        }
    }

    fun connectTo(connection: HermesConnection, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val result = gatewayRepo.connect(connection)
            if (result.isSuccess) {
                onSuccess()
            } else {
                _uiState.value = _uiState.value.copy(
                    authError = result.exceptionOrNull()?.localizedMessage ?: "Failed to connect"
                )
            }
        }
    }

    fun disconnect() {
        gatewayRepo.disconnect()
    }
}
