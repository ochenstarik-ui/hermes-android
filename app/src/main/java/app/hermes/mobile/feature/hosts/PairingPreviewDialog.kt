package app.hermes.mobile.feature.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermes.mobile.core.pairing.HermesPairingPayload
import app.hermes.mobile.core.network.HermesRestClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingPreviewDialog(
    payload: HermesPairingPayload,
    isExistingHost: Boolean,
    existingHostName: String?,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var probeStatus by remember { mutableStateOf("Probing...") }
    var allowCleartext by remember { mutableStateOf(payload.scheme == "http") }
    val coroutineScope = rememberCoroutineScope()
    val restClient = remember { HermesRestClient() }

    LaunchedEffect(payload) {
        coroutineScope.launch {
            try {
                val url = "${payload.scheme}://${payload.host}:${payload.port}"
                val result = withContext(Dispatchers.IO) {
                    restClient.getStatus(url, allowCleartext)
                }
                if (result.isSuccess) {
                    val status = result.getOrNull()
                    if (status != null) {
                        probeStatus = "Hermes v${status.version} | Auth: ${if (status.authRequired) "Required" else "None"}"
                    }
                } else {
                    probeStatus = "Probe failed: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                probeStatus = "Probe failed: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pairing Preview") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Host: ${payload.name}", style = MaterialTheme.typography.bodyLarge)
                Text("Address: ${payload.scheme}://${payload.host}:${payload.port}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                Text("Status: $probeStatus", style = MaterialTheme.typography.bodySmall)
                
                if (isExistingHost) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            "Will update endpoint for existing host '${existingHostName ?: payload.name}'",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                
                if (payload.scheme == "http") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Allow Cleartext", style = MaterialTheme.typography.bodyMedium)
                            Text("Security warning: HTTP is insecure", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Switch(
                            checked = allowCleartext,
                            onCheckedChange = { allowCleartext = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(allowCleartext) }) {
                Text(if (isExistingHost) "Update Host" else "Add Host")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}
