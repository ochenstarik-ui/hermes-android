package app.hermes.mobile.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.TokenVault
import app.hermes.mobile.core.storage.UnifiedSessionDao
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SettingsDiagnostics(
    val registeredHostsCount: Int = 0,
    val onlineHostsCount: Int = 0,
    val storedCredentialsCount: Int = 0
)

class SettingsViewModel(
    private val connectionManager: HermesConnectionManager,
    private val tokenVault: TokenVault,
    private val sessionDao: UnifiedSessionDao
) : ViewModel() {

    private val _diagnostics = MutableStateFlow(SettingsDiagnostics())
    val diagnostics: StateFlow<SettingsDiagnostics> = _diagnostics.asStateFlow()

    private val _cacheClearedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheClearedEvent: SharedFlow<Unit> = _cacheClearedEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            connectionManager.hosts.collect { hosts ->
                val online = hosts.count { it.lastKnownStatus == HostStatus.ONLINE }
                val stored = tokenVault.getAllHostIds().size
                _diagnostics.value = SettingsDiagnostics(
                    registeredHostsCount = hosts.size,
                    onlineHostsCount = online,
                    storedCredentialsCount = stored
                )
            }
        }
    }

    fun clearLocalCache() {
        viewModelScope.launch {
            sessionDao.clearAllLocalCache()
            _cacheClearedEvent.tryEmit(Unit)
        }
    }
}
