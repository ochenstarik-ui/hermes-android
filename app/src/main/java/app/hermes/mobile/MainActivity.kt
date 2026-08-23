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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.repository.ConnectionRepository
import app.hermes.mobile.core.repository.HermesGatewayRepository
import app.hermes.mobile.core.security.EncryptedTokenVault
import app.hermes.mobile.feature.chat.ChatScreen
import app.hermes.mobile.feature.chat.ChatViewModel
import app.hermes.mobile.feature.connections.ConnectionsScreen
import app.hermes.mobile.feature.connections.ConnectionsViewModel
import app.hermes.mobile.feature.sessions.SessionsScreen
import app.hermes.mobile.feature.sessions.SessionsViewModel
import app.hermes.mobile.feature.settings.SettingsScreen
import app.hermes.mobile.ui.theme.HermesAndroidTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val tokenVault = EncryptedTokenVault(applicationContext)
        val restClient = HermesRestClient()
        val gatewayClient = JsonRpcGatewayClient()
        val pkceAuthManager = PkceLoopbackAuthManager(restClient, tokenVault)
        val connectionRepo = ConnectionRepository(applicationContext)
        val gatewayRepo = HermesGatewayRepository(restClient, gatewayClient, tokenVault)

        setContent {
            HermesAndroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HermesAppNavigation(
                        connectionRepo = connectionRepo,
                        gatewayRepo = gatewayRepo,
                        tokenVault = tokenVault,
                        pkceAuthManager = pkceAuthManager
                    )
                }
            }
        }
    }
}

@Composable
fun HermesAppNavigation(
    connectionRepo: ConnectionRepository,
    gatewayRepo: HermesGatewayRepository,
    tokenVault: EncryptedTokenVault,
    pkceAuthManager: PkceLoopbackAuthManager
) {
    val navController = rememberNavController()

    val connectionsViewModel = remember {
        ConnectionsViewModel(connectionRepo, gatewayRepo, tokenVault, pkceAuthManager)
    }
    val sessionsViewModel = remember {
        SessionsViewModel(gatewayRepo)
    }
    val chatViewModel = remember {
        ChatViewModel(gatewayRepo)
    }

    NavHost(
        navController = navController,
        startDestination = "connections"
    ) {
        composable("connections") {
            ConnectionsScreen(
                viewModel = connectionsViewModel,
                onNavigateToSessions = { connId ->
                    sessionsViewModel.loadSessions()
                    navController.navigate("sessions/$connId")
                }
            )
        }

        composable(
            route = "sessions/{connectionId}",
            arguments = listOf(navArgument("connectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val connId = backStackEntry.arguments?.getString("connectionId") ?: ""
            SessionsScreen(
                viewModel = sessionsViewModel,
                connectionId = connId,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToChat = { durableSessionId ->
                    navController.navigate("chat/$connId/$durableSessionId")
                }
            )
        }

        composable(
            route = "chat/{connectionId}/{durableSessionId}",
            arguments = listOf(
                navArgument("connectionId") { type = NavType.StringType },
                navArgument("durableSessionId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val durableSessionId = backStackEntry.arguments?.getString("durableSessionId") ?: ""
            ChatScreen(
                viewModel = chatViewModel,
                durableSessionId = durableSessionId,
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
