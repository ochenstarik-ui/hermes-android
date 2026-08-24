package app.hermes.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.ToolActivity
import app.hermes.mobile.core.model.UnifiedMessage
import app.hermes.mobile.core.model.UnifiedMessageSource

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val approvals by viewModel.activeApprovals.collectAsState()
    val activeClarify by viewModel.activeClarify.collectAsState()
    val isExecuting by viewModel.isExecuting.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()

    val activeHost = hosts.find { it.id == currentSession?.activeHostId }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorMessage = uiState.error
    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank()) {
            val sanitized = sanitizeErrorMessage(errorMessage)
            val result = snackbarHostState.showSnackbar(
                message = sanitized,
                actionLabel = "Retry",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.submitPrompt()
            }
            viewModel.clearError()
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, approvals.size) {
        if (messages.isNotEmpty() || approvals.isNotEmpty()) {
            val totalCount = messages.size + approvals.size
            listState.animateScrollToItem(totalCount)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = currentSession?.title?.ifEmpty { "Unified Chat" } ?: "Unified Chat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // Active host selector chip
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { viewModel.setHostDropdownExpanded(true) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                    val isOnline = activeHost?.lastKnownStatus == HostStatus.ONLINE
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isOnline) Color(0xFF10B981) else Color(0xFF94A3B8))
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = activeHost?.displayName ?: "Select Host",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = "Switch Host",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                DropdownMenu(
                                    expanded = uiState.activeHostDropdownExpanded,
                                    onDismissRequest = { viewModel.setHostDropdownExpanded(false) }
                                ) {
                                    hosts.forEach { host ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val online = host.lastKnownStatus == HostStatus.ONLINE
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(if (online) Color(0xFF10B981) else Color(0xFF94A3B8))
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = host.displayName,
                                                        fontWeight = if (host.id == currentSession?.activeHostId) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            },
                                            onClick = { viewModel.switchActiveHost(host.id) }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (isExecuting) {
                            Button(
                                onClick = { viewModel.interruptSession() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                )

                // Multi-host Status Strip showing simultaneous statuses (e.g. PC1 Running, Linux Active, PC3 Offline)
                if (hosts.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(hosts, key = { it.id.value }) { host ->
                            val isRunning by viewModel.getHostExecuting(host.id).collectAsState()
                            val isActive = host.id == currentSession?.activeHostId

                            HostStatusChip(
                                host = host,
                                isActive = isActive,
                                isRunning = isRunning,
                                onClick = { viewModel.switchActiveHost(host.id) },
                                onStop = { viewModel.interruptHost(host.id) }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            ChatInputBar(
                text = uiState.inputText,
                onTextChange = { viewModel.updateInputText(it) },
                onSend = { viewModel.submitPrompt() },
                isExecuting = isExecuting,
                onStop = { viewModel.interruptSession() }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                if (message.source == UnifiedMessageSource.TRANSFER) {
                    TransferSeparator(message = message)
                } else {
                    val host = hosts.find { it.id == message.hostId }
                    MessageItem(message = message, hostDisplayName = host?.displayName)
                }
            }

            items(approvals, key = { it.hostId.value + it.runtimeSessionId.value + it.approval.requestId }) { approval ->
                ApprovalCard(
                    attributedApproval = approval,
                    onRespond = { choice, all ->
                        viewModel.respondApproval(approval.hostId, approval.runtimeSessionId, approval.approval.requestId, choice, all)
                    }
                )
            }

            if (isExecuting && messages.lastOrNull()?.isStreaming != true && approvals.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        val hostLabel = activeHost?.displayName ?: "Hermes"
                        Text(
                            "$hostLabel is thinking…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        val clarify = activeClarify
        if (clarify != null) {
            ClarifyDialog(
                attributedClarify = clarify,
                onDismiss = {
                    viewModel.dismissClarify(clarify)
                },
                onSubmit = { value ->
                    viewModel.respondClarify(clarify, value)
                }
            )
        }
    }
}

@Composable
fun HostStatusChip(
    host: HermesHost,
    isActive: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit
) {
    val statusText = when {
        isRunning -> "Running"
        isActive -> "Active"
        host.lastKnownStatus == HostStatus.ONLINE -> "Online"
        host.lastKnownStatus == HostStatus.CONNECTING -> "Connecting"
        host.lastKnownStatus == HostStatus.AUTH_EXPIRED -> "Auth Expired"
        else -> "Offline"
    }

    val chipBg = when {
        isRunning -> Color(0xFFF59E0B).copy(alpha = 0.15f)
        isActive -> Color(0xFF38BDF8).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }

    val chipBorder = when {
        isRunning -> Color(0xFFF59E0B)
        isActive -> Color(0xFF38BDF8)
        else -> Color(0xFF475569)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = Color(0xFFF59E0B)
            )
        } else {
            val dotColor = when (host.lastKnownStatus) {
                HostStatus.ONLINE -> Color(0xFF10B981)
                HostStatus.CONNECTING -> Color(0xFFF59E0B)
                HostStatus.AUTH_EXPIRED -> Color(0xFFEF4444)
                else -> Color(0xFF94A3B8)
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${host.displayName}: $statusText",
            fontSize = 11.sp,
            fontWeight = if (isActive || isRunning) FontWeight.Bold else FontWeight.Normal,
            color = if (isRunning) Color(0xFFF59E0B) else if (isActive) Color(0xFF38BDF8) else MaterialTheme.colorScheme.onSurface
        )

        if (isRunning) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .clickable { onStop() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop Host",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
fun TransferSeparator(message: UnifiedMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF38BDF8).copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = Color(0xFF0284C7),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = message.content,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0284C7)
                )
            }
        }
    }
}

@Composable
fun MessageItem(message: UnifiedMessage, hostDisplayName: String?) {
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Host attribution badge for assistant responses
        if (!isUser && hostDisplayName != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = hostDisplayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Thinking Collapsible
        if (!message.thinking.isNullOrBlank()) {
            ThinkingSection(thinking = message.thinking)
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Tools Invocation Cards
        if (message.tools.isNotEmpty()) {
            for (tool in message.tools) {
                ToolActivityCard(tool = tool)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Message Content Bubble
        if (message.content.isNotBlank() || (message.isStreaming && message.tools.isEmpty())) {
            Card(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (message.isStreaming) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ThinkingSection(thinking: String) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFF59E0B)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Reasoning & Thoughts",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = thinking,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ToolActivityCard(tool: ToolActivity) {
    val isRunning = tool.status == "running" || tool.status == "generating"
    val isCompleted = tool.status == "completed"

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = Modifier.fillMaxWidth(0.9f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        tool.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Running", fontSize = 10.sp, color = Color(0xFF38BDF8))
                    } else if (isCompleted) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Done", fontSize = 10.sp, color = Color(0xFF10B981))
                    }
                }
            }

            if (!tool.progress.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.progress,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            if (!tool.result.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = tool.result,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (tool.isError) Color(0xFFEF4444) else Color(0xFFE2E8F0),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isExecuting: Boolean,
    onStop: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Message Hermes…") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            if (isExecuting) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = text.isNotBlank(),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

fun sanitizeErrorMessage(error: String): String {
    val clean = error.lineSequence().firstOrNull()?.trim() ?: "An error occurred"
    return clean.take(150)
}
