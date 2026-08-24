package app.hermes.mobile

import android.app.Application
import app.hermes.mobile.core.storage.MigrationHelper
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HermesApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = HermesAppContainer(this)
        container.applicationScope.launch {
            MigrationHelper.migrateLegacyConnections(this@HermesApplication, container.db.hostDao())
            container.networkMonitor?.start()
        }
        container.applicationScope.launch {
            container.unifiedSessionRepo.hasActiveTasks.collect { hasActive ->
                if (hasActive) {
                    app.hermes.mobile.core.service.HermesTaskForegroundService.startIfRequired(this@HermesApplication, true)
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        container.networkMonitor?.stop()
        container.connectionManager.close()
        container.applicationScope.cancel()
    }
}
