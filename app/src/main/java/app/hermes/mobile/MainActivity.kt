package app.hermes.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.storage.MigrationHelper
import app.hermes.mobile.feature.chat.ChatScreen
import app.hermes.mobile.feature.chat.ChatViewModel
import app.hermes.mobile.feature.hosts.HostsScreen
import app.hermes.mobile.feature.hosts.HostsViewModel
import app.hermes.mobile.feature.native_sessions.NativeSessionsScreen
import app.hermes.mobile.feature.native_sessions.NativeSessionsViewModel
import app.hermes.mobile.feature.settings.SettingsScreen
import app.hermes.mobile.feature.unified_sessions.UnifiedSessionsScreen
import app.hermes.mobile.feature.unified_sessions.UnifiedSessionsViewModel
import app.hermes.mobile.ui.theme.HermesAndroidTheme
import kotlinx.coroutines.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class AppViewModelFactory(
    private val container: AppContainer,
    private val extraArg: Any? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(UnifiedSessionsViewModel::class.java) -> {
                UnifiedSessionsViewModel(
                    sessionRepo = container.unifiedSessionRepo,
                    connectionManager = container.connectionManager
                ) as T
            }
            modelClass.isAssignableFrom(HostsViewModel::class.java) -> {
                HostsViewModel(
                    connectionManager = container.connectionManager,
                    tokenVault = container.tokenVault,
                    restClient = container.restClient,
                    pkceAuthManager = container.pkceAuthManager
                ) as T
            }
            modelClass.isAssignableFrom(ChatViewModel::class.java) -> {
                val sessionId = extraArg as? UnifiedSessionId
                    ?: throw IllegalArgumentException("ChatViewModel requires a UnifiedSessionId extraArg")
                ChatViewModel(
                    sessionRepo = container.unifiedSessionRepo,
                    connectionManager = container.connectionManager,
                    sessionId = sessionId
                ) as T
            }
            modelClass.isAssignableFrom(NativeSessionsViewModel::class.java) -> {
                val hostId = extraArg as? HermesHostId
                    ?: throw IllegalArgumentException("NativeSessionsViewModel requires a HermesHostId extraArg")
                NativeSessionsViewModel(
                    connectionManager = container.connectionManager,
                    hostId = hostId
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (applicationContext as HermesApplication).container
        val db = container.db
        val hostDao = db.hostDao()

        // Migrate legacy connections from DataStore if present
        lifecycleScope.launch {
            MigrationHelper.migrateLegacyConnections(applicationContext, hostDao)
        }

        handleAuthIntent(intent)

        setContent {
            HermesAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesUnifiedAppNavigation(container = container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "hermes" && uri.host == "auth-callback") {
            lifecycleScope.launch {
                val container = (applicationContext as HermesApplication).container
                container.pkceAuthManager.handleAuthCallbackUri(uri)
            }
        }
    }
}

@Composable
fun HermesUnifiedAppNavigation(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "unified_sessions"
    ) {
        composable("unified_sessions") {
            val unifiedSessionsViewModel: UnifiedSessionsViewModel = viewModel(
                factory = AppViewModelFactory(container)
            )
            UnifiedSessionsScreen(
                viewModel = unifiedSessionsViewModel,
                onNavigateToChat = { sessionId ->
                    navController.navigate("chat/${sessionId.value}")
                },
                onNavigateToHosts = {
                    navController.navigate("hosts")
                }
            )
        }

        composable(
            route = "chat/{unifiedSessionId}",
            arguments = listOf(navArgument("unifiedSessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val sessionIdStr = backStackEntry.arguments?.getString("unifiedSessionId") ?: ""
            val sessionId = UnifiedSessionId(sessionIdStr)
            val chatViewModel: ChatViewModel = viewModel(
                factory = AppViewModelFactory(container, extraArg = sessionId)
            )
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("hosts") {
            val hostsViewModel: HostsViewModel = viewModel(
                factory = AppViewModelFactory(container)
            )
            HostsScreen(
                viewModel = hostsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToNativeSessions = { hostId ->
                    navController.navigate("native_sessions/${hostId.value}")
                }
            )
        }

        composable(
            route = "native_sessions/{hostId}",
            arguments = listOf(navArgument("hostId") { type = NavType.StringType })
        ) { backStackEntry ->
            val hostIdStr = backStackEntry.arguments?.getString("hostId") ?: ""
            val hostId = HermesHostId(hostIdStr)
            val nativeSessionsViewModel: NativeSessionsViewModel = viewModel(
                factory = AppViewModelFactory(container, extraArg = hostId)
            )
            NativeSessionsScreen(
                viewModel = nativeSessionsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
