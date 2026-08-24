package app.hermes.mobile.feature.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hermes.mobile.core.pairing.HermesPairingPayload
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.model.HermesServerStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

sealed interface PairingProbeState {
    data object Idle : PairingProbeState
    data object Probing : PairingProbeState
    data class Success(val status: HermesServerStatus) : PairingProbeState
    data class Failed(val message: String) : PairingProbeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingPreviewDialog(
    payload: HermesPairingPayload,
    isExistingHost: Boolean,
    existingHostName: String?,
    isEndpointChanged: Boolean,
    oldEndpointUrl: String?,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var probeState by remember { mutableStateOf<PairingProbeState>(PairingProbeState.Probing) }
    var allowCleartext by remember { mutableStateOf(payload.scheme == "http") }
    val coroutineScope = rememberCoroutineScope()
    val restClient = remember { HermesRestClient() }

    fun doProbe() {
        probeState = PairingProbeState.Probing
        coroutineScope.launch {
            try {
                val url = "${payload.scheme}://${payload.host}:${payload.port}"
                val result = withContext(Dispatchers.IO) {
                    restClient.getStatus(url, allowCleartext)
                }
                if (result.isSuccess) {
                    val status = result.getOrNull()
                    if (status != null) {
                        probeState = PairingProbeState.Success(status)
                    } else {
                        probeState = PairingProbeState.Failed("Empty status")
                    }
                } else {
                    probeState = PairingProbeState.Failed(result.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                probeState = PairingProbeState.Failed(e.message ?: "Unknown error")
            }
        }
    }

    LaunchedEffect(payload) {
        doProbe()
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pairing Preview") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Host: ${payload.name}", style = MaterialTheme.typography.bodyLarge)
                Text("Address: ${payload.scheme}://${payload.host}:${payload.port}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                when (val state = probeState) {
                    is PairingProbeState.Probing -> {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Probing status...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is PairingProbeState.Failed -> {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("Probe failed: ${state.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(onClick = { doProbe() }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    is PairingProbeState.Success -> {
                        Surface(color = androidx.compose.ui.graphics.Color(0xFFD1FAE5), shape = MaterialTheme.shapes.small) {
                            Text(
                                "Hermes v${state.status.version} | Auth: ${if (state.status.authRequired) "Required" else "None"}",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFF065F46)
                            )
                        }
                    }
                    PairingProbeState.Idle -> {}
                }

                if (isExistingHost) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isEndpointChanged) {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                "Endpoint for this host has changed from $oldEndpointUrl to ${payload.scheme}://${payload.host}:${payload.port}. For security, saved login credentials will be cleared. Fresh login will be required.",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    } else {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                "Will update endpoint for existing host '${existingHostName ?: payload.name}'",
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
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
            Button(
                onClick = { onConfirm(allowCleartext) },
                enabled = probeState is PairingProbeState.Success
            ) {
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
