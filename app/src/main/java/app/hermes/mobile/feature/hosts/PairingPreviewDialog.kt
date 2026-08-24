package app.hermes.mobile.feature.hosts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hermes.mobile.R
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.pairing.HermesPairingPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        title = { Text(stringResource(R.string.pairing_preview)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.host_format, payload.name), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.address_format, "${payload.scheme}://${payload.host}:${payload.port}"), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                when (val state = probeState) {
                    is PairingProbeState.Probing -> {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.probing_status), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is PairingProbeState.Failed -> {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(stringResource(R.string.probe_failed_format, state.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(onClick = { doProbe() }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text(stringResource(R.string.retry), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    is PairingProbeState.Success -> {
                        Surface(color = androidx.compose.ui.graphics.Color(0xFFD1FAE5), shape = MaterialTheme.shapes.small) {
                            val authStr = if (state.status.authRequired) stringResource(R.string.auth_required_label) else stringResource(R.string.auth_none_label)
                            Text(
                                "Hermes v${state.status.version} | Auth: $authStr",
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
                                stringResource(R.string.endpoint_changed_warning, oldEndpointUrl ?: "", "${payload.scheme}://${payload.host}:${payload.port}"),
                                modifier = Modifier.padding(8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    } else {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                stringResource(R.string.update_endpoint_notice, existingHostName ?: payload.name),
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
                            Text(stringResource(R.string.allow_cleartext), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.http_insecure_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                Text(if (isExistingHost) stringResource(R.string.update_host) else stringResource(R.string.add_host))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
