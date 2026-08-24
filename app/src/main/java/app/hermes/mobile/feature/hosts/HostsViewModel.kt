package app.hermes.mobile.feature.hosts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class HostsUiState(
    val isTesting: Boolean = false,
    val testStatus: HermesServerStatus? = null,
    val testError: String? = null,
    val isAuthenticating: Boolean = false,
    val authError: String? = null,
    val qrScanActive: Boolean = false,
    val scannedPayload: app.hermes.mobile.core.pairing.HermesPairingPayload? = null,
    val qrScanError: String? = null
)

class HostsViewModel(
    val connectionManager: HermesConnectionManager,
    val tokenVault: TokenVault,
    val restClient: HermesRestClient = HermesRestClient(),
    val pkceAuthManager: PkceLoopbackAuthManager = PkceLoopbackAuthManager(restClient, tokenVault)
) : ViewModel() {

    val hosts: StateFlow<List<HermesHost>> = connectionManager.hosts
    val activeHostId: StateFlow<HermesHostId?> = connectionManager.activeHostId

    private val _uiState = MutableStateFlow(HostsUiState())
    val uiState: StateFlow<HostsUiState> = _uiState.asStateFlow()

    fun testHostConnection(baseUrl: String, allowCleartext: Boolean) {
        _uiState.value = _uiState.value.copy(isTesting = true, testStatus = null, testError = null)
        viewModelScope.launch {
            val result = restClient.getStatus(baseUrl, allowCleartext)
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isTesting = false, testStatus = result.getOrNull())
            } else {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testError = result.exceptionOrNull()?.message ?: "Failed to reach host"
                )
            }
        }
    }

    fun saveHost(name: String, baseUrl: String, allowCleartext: Boolean) {
        val host = HermesHost(
            id = HermesHostId(UUID.randomUUID().toString()),
            displayName = name.ifBlank { "Hermes Host" },
            baseUrl = baseUrl,
            allowCleartext = allowCleartext,
            enabled = true,
            lastSeenAt = System.currentTimeMillis(),
            lastKnownStatus = HostStatus.OFFLINE
        )
        viewModelScope.launch {
            connectionManager.addHost(host)
        }
    }

    fun removeHost(hostId: HermesHostId) {
        viewModelScope.launch {
            connectionManager.removeHost(hostId)
        }
    }

    fun connectHost(hostId: HermesHostId, onConnected: (() -> Unit)? = null) {
        viewModelScope.launch {
            val res = connectionManager.connectHost(hostId)
            if (res.isSuccess) {
                onConnected?.invoke()
            } else {
                _uiState.value = _uiState.value.copy(
                    authError = res.exceptionOrNull()?.message
                )
            }
        }
    }

    fun disconnectHost(hostId: HermesHostId) {
        connectionManager.disconnectHost(hostId)
    }

    fun isHostAuthenticated(hostId: HermesHostId): Boolean {
        return tokenVault.getTokens(hostId.value) != null
    }

    fun startSignIn(context: Context, host: HermesHost, onCompleted: (() -> Unit)? = null) {
        _uiState.value = _uiState.value.copy(isAuthenticating = true, authError = null)
        viewModelScope.launch {
            val result = pkceAuthManager.startAuthFlow(
                context = context,
                connectionId = host.id.value,
                baseUrl = host.baseUrl,
                allowCleartext = host.allowCleartext
            )
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(isAuthenticating = false)
                onCompleted?.invoke()
            } else {
                _uiState.value = _uiState.value.copy(
                    isAuthenticating = false,
                    authError = result.exceptionOrNull()?.message ?: "Authentication failed"
                )
            }
        }
    }

    fun startQrScan() {
        _uiState.value = _uiState.value.copy(qrScanActive = true, scannedPayload = null, qrScanError = null)
    }

    fun dismissQrScan() {
        _uiState.value = _uiState.value.copy(qrScanActive = false, scannedPayload = null, qrScanError = null)
    }

    fun onQrScanned(rawUri: String) {
        when (val result = app.hermes.mobile.core.pairing.HermesPairingParser.parse(rawUri)) {
            is app.hermes.mobile.core.pairing.PairingValidationResult.Success -> {
                _uiState.value = _uiState.value.copy(qrScanActive = false, scannedPayload = result.payload, qrScanError = null)
            }
            is app.hermes.mobile.core.pairing.PairingValidationResult.Expired -> {
                _uiState.value = _uiState.value.copy(qrScanActive = false, qrScanError = "QR code has expired")
            }
            is app.hermes.mobile.core.pairing.PairingValidationResult.InvalidPayload -> {
                _uiState.value = _uiState.value.copy(qrScanActive = false, qrScanError = "Invalid QR code: ${result.reason}")
            }
            is app.hermes.mobile.core.pairing.PairingValidationResult.InvalidScheme -> {
                _uiState.value = _uiState.value.copy(qrScanActive = false, qrScanError = "Invalid scheme: ${result.reason}")
            }
            is app.hermes.mobile.core.pairing.PairingValidationResult.InvalidVersion -> {
                _uiState.value = _uiState.value.copy(qrScanActive = false, qrScanError = "Unsupported QR version: ${result.version}")
            }
        }
    }

    fun confirmPairing(payload: app.hermes.mobile.core.pairing.HermesPairingPayload, allowCleartext: Boolean) {
        viewModelScope.launch {
            val existingHost = connectionManager.hostDao.getHost(payload.hostId)
            val hostToConnect = if (existingHost != null) {
                val oldCanonical = app.hermes.mobile.core.pairing.CanonicalEndpoint.fromBaseUrl(existingHost.baseUrl)
                if (oldCanonical != payload.canonicalEndpoint) {
                    connectionManager.disconnectHost(HermesHostId(payload.hostId))
                    tokenVault.clearTokens(payload.hostId)
                }
                val updatedHost = HermesHost(
                    id = HermesHostId(payload.hostId),
                    displayName = payload.name,
                    baseUrl = "${payload.scheme}://${payload.host}:${payload.port}",
                    allowCleartext = allowCleartext,
                    enabled = existingHost.enabled,
                    lastSeenAt = existingHost.lastSeenAt,
                    lastKnownStatus = HostStatus.valueOf(existingHost.lastKnownStatus)
                )
                connectionManager.updateHost(updatedHost)
                updatedHost
            } else {
                val newHost = HermesHost(
                    id = HermesHostId(payload.hostId),
                    displayName = payload.name,
                    baseUrl = "${payload.scheme}://${payload.host}:${payload.port}",
                    allowCleartext = allowCleartext,
                    enabled = true,
                    lastSeenAt = System.currentTimeMillis(),
                    lastKnownStatus = HostStatus.OFFLINE
                )
                connectionManager.addHost(newHost)
                newHost
            }
            _uiState.value = _uiState.value.copy(scannedPayload = null)
            connectHost(hostToConnect.id)
        }
    }
}
