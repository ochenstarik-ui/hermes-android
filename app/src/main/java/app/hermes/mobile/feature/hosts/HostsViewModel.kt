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
import app.hermes.mobile.core.pairing.CanonicalEndpoint
import app.hermes.mobile.core.pairing.HermesPairingParser
import app.hermes.mobile.core.pairing.PairingPayloadV1
import app.hermes.mobile.core.pairing.PairingResult
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.TokenVault
import app.hermes.mobile.core.storage.UsedNonceDao
import app.hermes.mobile.core.storage.UsedNonceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

fun normalizeHostUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) {
        throw IllegalArgumentException("Host URL cannot be empty")
    }
    val schemeIndex = trimmed.indexOf("://")
    val withScheme = if (schemeIndex != -1) {
        val explicitScheme = trimmed.substring(0, schemeIndex).lowercase()
        if (explicitScheme != "http" && explicitScheme != "https") {
            throw IllegalArgumentException("Unsupported scheme '$explicitScheme', only http and https are supported")
        }
        trimmed
    } else {
        "https://$trimmed"
    }
    val withoutTrailingSlash = withScheme.trimEnd('/')

    val uri = try {
        URI(withoutTrailingSlash)
    } catch (e: Exception) {
        throw IllegalArgumentException("Malformed host URL: ${e.message}")
    }

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        throw IllegalArgumentException("Unsupported scheme '$scheme', only http and https are supported")
    }

    val auth = uri.rawAuthority ?: ""
    val hostPart: String
    val portStr: String
    if (auth.startsWith("[")) {
        val closingBracket = auth.indexOf(']')
        if (closingBracket == -1) {
            throw IllegalArgumentException("Unclosed IPv6 bracket in authority: $auth")
        }
        hostPart = auth.substring(0, closingBracket + 1)
        portStr = if (auth.length > closingBracket + 2 && auth[closingBracket + 1] == ':') {
            auth.substring(closingBracket + 2)
        } else {
            ""
        }
    } else if (auth.contains(":")) {
        hostPart = auth.substringBefore(":")
        portStr = auth.substringAfter(":")
    } else {
        hostPart = auth
        portStr = ""
    }

    if (hostPart.isBlank()) {
        throw IllegalArgumentException("Host address is missing")
    }

    if (portStr.isNotEmpty()) {
        val parsedPort = portStr.toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            throw IllegalArgumentException("Invalid port: $portStr")
        }
    }

    return withoutTrailingSlash
}

data class HostsUiState(
    val isTesting: Boolean = false,
    val testStatus: HermesServerStatus? = null,
    val testError: String? = null,
    val isAuthenticating: Boolean = false,
    val authError: String? = null,
    val qrScanActive: Boolean = false,
    val scannedPayload: PairingPayloadV1? = null,
    val qrScanError: String? = null
)

class HostsViewModel(
    val connectionManager: HermesConnectionManager,
    val tokenVault: TokenVault,
    val restClient: HermesRestClient = HermesRestClient(),
    val pkceAuthManager: PkceLoopbackAuthManager = PkceLoopbackAuthManager(restClient, tokenVault),
    val usedNonceDao: UsedNonceDao? = null
) : ViewModel() {

    val hosts: StateFlow<List<HermesHost>> = connectionManager.hosts
    val activeHostId: StateFlow<HermesHostId?> = connectionManager.activeHostId

    private val _uiState = MutableStateFlow(HostsUiState())
    val uiState: StateFlow<HostsUiState> = _uiState.asStateFlow()

    fun testHostConnection(baseUrl: String, allowCleartext: Boolean) {
        _uiState.value = _uiState.value.copy(isTesting = true, testStatus = null, testError = null)
        val normalizedUrl = try {
            normalizeHostUrl(baseUrl)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isTesting = false, testError = e.message ?: "Invalid host URL")
            return
        }
        viewModelScope.launch {
            val result = restClient.getStatus(normalizedUrl, allowCleartext)
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
        val normalizedUrl = try {
            normalizeHostUrl(baseUrl)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(testError = e.message ?: "Invalid host URL")
            return
        }
        val host = HermesHost(
            id = HermesHostId(UUID.randomUUID().toString()),
            displayName = name.ifBlank { "Hermes Host" },
            baseUrl = normalizedUrl,
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
        when (val result = HermesPairingParser.parse(rawUri)) {
            is PairingResult.Success -> {
                val payload = result.payload
                viewModelScope.launch {
                    val isUsed = usedNonceDao?.isNonceUsed(payload.nonce) ?: false
                    if (isUsed) {
                        _uiState.value = _uiState.value.copy(
                            qrScanActive = false,
                            scannedPayload = null,
                            qrScanError = "QR code nonce has already been used on this device"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            qrScanActive = false,
                            scannedPayload = payload,
                            qrScanError = null
                        )
                    }
                }
            }
            is PairingResult.Failure -> {
                _uiState.value = _uiState.value.copy(
                    qrScanActive = false,
                    scannedPayload = null,
                    qrScanError = result.error.message
                )
            }
        }
    }

    fun confirmPairing(payload: PairingPayloadV1, allowCleartext: Boolean) {
        viewModelScope.launch {
            if (usedNonceDao != null) {
                val isUsed = usedNonceDao.isNonceUsed(payload.nonce)
                if (isUsed) {
                    _uiState.value = _uiState.value.copy(
                        scannedPayload = null,
                        qrScanError = "QR code nonce has already been used on this device"
                    )
                    return@launch
                }
                usedNonceDao.insertNonce(
                    UsedNonceEntity(
                        nonce = payload.nonce,
                        expiresAt = payload.expiresAt,
                        usedAt = System.currentTimeMillis()
                    )
                )
                usedNonceDao.purgeExpiredNonces(System.currentTimeMillis() / 1000)
            }

            val existingHost = connectionManager.hostDao.getHost(payload.hostId)
            val hostToConnect = if (existingHost != null) {
                val oldCanonical = CanonicalEndpoint.fromBaseUrl(existingHost.baseUrl)
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
                    lastKnownStatus = HostStatus.fromStringOrOffline(existingHost.lastKnownStatus)
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(
            testError = null,
            authError = null,
            qrScanError = null
        )
    }
}
