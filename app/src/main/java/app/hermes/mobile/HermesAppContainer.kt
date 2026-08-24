package app.hermes.mobile

import android.content.Context
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.auth.PkceStateStore
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.LiveNetworkMonitor
import app.hermes.mobile.core.network.NetworkMonitor
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.EncryptedTokenVault
import app.hermes.mobile.core.storage.HermesDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

interface AppContainer {
    val db: HermesDatabase
    val tokenVault: EncryptedTokenVault
    val restClient: HermesRestClient
    val pkceAuthManager: PkceLoopbackAuthManager
    val connectionManager: HermesConnectionManager
    val unifiedSessionRepo: UnifiedSessionRepository
    val applicationScope: CoroutineScope
    val stateStore: PkceStateStore? get() = null
    val networkMonitor: NetworkMonitor? get() = null
}

class HermesAppContainer(private val context: Context) : AppContainer {
    override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val db: HermesDatabase by lazy {
        HermesDatabase.getInstance(context)
    }

    override val tokenVault: EncryptedTokenVault by lazy {
        EncryptedTokenVault(context)
    }

    override val restClient: HermesRestClient by lazy {
        HermesRestClient()
    }

    override val stateStore: PkceStateStore by lazy {
        PkceStateStore(context)
    }

    override val pkceAuthManager: PkceLoopbackAuthManager by lazy {
        PkceLoopbackAuthManager(restClient, tokenVault, stateStore)
    }

    override val connectionManager: HermesConnectionManager by lazy {
        HermesConnectionManager(
            hostDao = db.hostDao(),
            tokenVault = tokenVault,
            restClient = restClient,
            scope = applicationScope
        )
    }

    override val unifiedSessionRepo: UnifiedSessionRepository by lazy {
        UnifiedSessionRepository(
            connectionManager = connectionManager,
            sessionDao = db.unifiedSessionDao(),
            scope = applicationScope
        )
    }

    override val networkMonitor: NetworkMonitor by lazy {
        LiveNetworkMonitor(context, connectionManager, applicationScope)
    }
}
