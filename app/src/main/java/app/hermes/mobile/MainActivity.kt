package app.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.UnifiedSessionId
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.EncryptedTokenVault
import app.hermes.mobile.core.storage.HermesDatabase
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
        val tokenVault = container.tokenVault
        val pkceAuthManager = container.pkceAuthManager
        val connectionManager = container.connectionManager
        val unifiedSessionRepo = container.unifiedSessionRepo

        setContent {
            HermesAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesUnifiedAppNavigation(
                        connectionManager = connectionManager,
                        sessionRepo = unifiedSessionRepo,
                        tokenVault = tokenVault,
                        pkceAuthManager = pkceAuthManager
                    )
                }
            }
        }
    }
}

@Composable
fun HermesUnifiedAppNavigation(
    connectionManager: HermesConnectionManager,
    sessionRepo: UnifiedSessionRepository,
    tokenVault: EncryptedTokenVault,
    pkceAuthManager: PkceLoopbackAuthManager
) {
    val navController = rememberNavController()

    val unifiedSessionsViewModel = remember {
        UnifiedSessionsViewModel(sessionRepo, connectionManager)
    }
    val hostsViewModel = remember {
        HostsViewModel(connectionManager, tokenVault, pkceAuthManager = pkceAuthManager)
    }

    NavHost(
        navController = navController,
        startDestination = "unified_sessions"
    ) {
        composable("unified_sessions") {
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
            val chatViewModel = remember(sessionIdStr) {
                ChatViewModel(sessionRepo, connectionManager, sessionId)
            }
            ChatScreen(
                viewModel = chatViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("hosts") {
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
            val nativeSessionsViewModel = remember(hostIdStr) {
                NativeSessionsViewModel(connectionManager, hostId)
            }
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
