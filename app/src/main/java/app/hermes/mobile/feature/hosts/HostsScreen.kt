package app.hermes.mobile.feature.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    viewModel: HostsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNativeSessions: (HermesHostId) -> Unit
) {
    val context = LocalContext.current
    val hosts by viewModel.hosts.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes Hosts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startQrScan() }) {
                        Icon(Icons.Default.Add, contentDescription = "Scan QR")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Host")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (uiState.authError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.authError ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (hosts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No Hermes hosts registered",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap '+' to add a workstation, server, or cloud host.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(hosts, key = { it.id.value }) { host ->
                        val isAuth = viewModel.isHostAuthenticated(host.id)
                        HostCard(
                            host = host,
                            isAuthenticated = isAuth,
                            isAuthenticating = uiState.isAuthenticating,
                            onConnect = { viewModel.connectHost(host.id) },
                            onDisconnect = { viewModel.disconnectHost(host.id) },
                            onSignIn = {
                                viewModel.startSignIn(context, host) {
                                    viewModel.connectHost(host.id)
                                }
                            },
                            onOpenNativeSessions = { onNavigateToNativeSessions(host.id) },
                            onDelete = { viewModel.removeHost(host.id) }
                        )
                    }
                }
            }
        }

                if (showAddDialog) {
            AddHostDialog(
                uiState = uiState,
                onDismiss = { showAddDialog = false },
                onTest = { url, cleartext -> viewModel.testHostConnection(url, cleartext) },
                onSave = { name, url, cleartext ->
                    viewModel.saveHost(name, url, cleartext)
                    showAddDialog = false
                }
            )
        }

        if (uiState.qrScanActive) {
            QrScannerSheet(
                onQrScanned = { viewModel.onQrScanned(it) },
                onDismiss = { viewModel.dismissQrScan() }
            )
        }

        uiState.scannedPayload?.let { payload ->
            val existingHost = hosts.find { it.id.value == payload.hostId }
            PairingPreviewDialog(
                payload = payload,
                isExistingHost = existingHost != null,
                existingHostName = existingHost?.displayName,
                onConfirm = { allowCleartext ->
                    viewModel.confirmPairing(payload, allowCleartext)
                },
                onCancel = { viewModel.dismissQrScan() }
            )
        }
        
        if (uiState.qrScanError != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissQrScan() },
                title = { Text("QR Scan Error") },
                text = { Text(uiState.qrScanError ?: "") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissQrScan() }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
fun HostCard(
    host: HermesHost,
    isAuthenticated: Boolean,
    isAuthenticating: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSignIn: () -> Unit,
    onOpenNativeSessions: () -> Unit,
    onDelete: () -> Unit
) {
    val isOnline = host.lastKnownStatus == HostStatus.ONLINE
    val isConnecting = host.lastKnownStatus == HostStatus.CONNECTING

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = host.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (host.allowCleartext) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFF59E0B).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("LAN / HTTP", fontSize = 10.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(onClick = onOpenNativeSessions) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Native Sessions")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badgeColor = when (host.lastKnownStatus) {
                        HostStatus.ONLINE -> Color(0xFF10B981)
                        HostStatus.CONNECTING -> Color(0xFF38BDF8)
                        HostStatus.AUTH_REQUIRED, HostStatus.AUTH_EXPIRED -> Color(0xFFF59E0B)
                        HostStatus.ERROR -> Color(0xFFEF4444)
                        HostStatus.OFFLINE -> Color(0xFF94A3B8)
                    }
                    val badgeText = when (host.lastKnownStatus) {
                        HostStatus.ONLINE -> "Online"
                        HostStatus.CONNECTING -> "Connecting…"
                        HostStatus.AUTH_REQUIRED -> "Auth Required"
                        HostStatus.AUTH_EXPIRED -> "Auth Expired"
                        HostStatus.ERROR -> "Error"
                        HostStatus.OFFLINE -> if (isAuthenticated) "Ready (Auth Saved)" else "Offline"
                    }

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(badgeText, style = MaterialTheme.typography.bodySmall, color = badgeColor, fontWeight = FontWeight.Medium)
                }

                Row {
                    if (host.lastKnownStatus == HostStatus.AUTH_REQUIRED || host.lastKnownStatus == HostStatus.AUTH_EXPIRED || !isAuthenticated) {
                        OutlinedButton(
                            onClick = onSignIn,
                            enabled = !isAuthenticating,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isAuthenticating) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                            } else {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Sign In", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (isOnline) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Disconnect", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = onConnect,
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text("Connect", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddHostDialog(
    uiState: HostsUiState,
    onDismiss: () -> Unit,
    onTest: (String, Boolean) -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("http://10.0.2.2:9119") }
    var allowCleartext by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Hermes Host", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Host Name (e.g. Linux Server, Office PC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (http://... or https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Cleartext HTTP (LAN)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Permits unencrypted local network traffic.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowCleartext,
                        onCheckedChange = { allowCleartext = it }
                    )
                }

                if (allowCleartext) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "⚠️ Security Notice: Plain HTTP is unencrypted. Use only on trusted private LANs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD97706)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onTest(baseUrl, allowCleartext) },
                    enabled = !uiState.isTesting && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing…")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection (/api/status)")
                    }
                }

                if (uiState.testStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Status OK (v${uiState.testStatus.version ?: "1.0"}, Auth: ${if (uiState.testStatus.authRequired) "Required" else "None"})",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF10B981)
                        )
                    }
                }

                if (uiState.testError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            uiState.testError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (baseUrl.isNotBlank()) {
                        onSave(name, baseUrl, allowCleartext)
                    }
                },
                enabled = baseUrl.isNotBlank()
            ) {
                Text("Save Host")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
