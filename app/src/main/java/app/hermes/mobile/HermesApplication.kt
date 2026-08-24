package app.hermes.mobile

import android.app.Application
import kotlinx.coroutines.cancel

class HermesApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = HermesAppContainer(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        container.applicationScope.cancel()
    }
}
