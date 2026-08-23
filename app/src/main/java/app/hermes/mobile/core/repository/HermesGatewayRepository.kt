package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

class HermesGatewayRepository(
    val restClient: HermesRestClient,
    val gatewayClient: JsonRpcGatewayClient,
    val tokenVault: TokenVault,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val _activeConnection = MutableStateFlow<HermesConnection?>(null)
    val activeConnection: StateFlow<HermesConnection?> = _activeConnection.asStateFlow()

    private val _serverStatus = MutableStateFlow<HermesServerStatus?>(null)
    val serverStatus: StateFlow<HermesServerStatus?> = _serverStatus.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = gatewayClient.connectionState

    private val _activeDurableId = MutableStateFlow<DurableSessionId?>(null)
    val activeDurableId: StateFlow<DurableSessionId?> = _activeDurableId.asStateFlow()

    private val _activeRuntimeId = MutableStateFlow<RuntimeSessionId?>(null)
    val activeRuntimeId: StateFlow<RuntimeSessionId?> = _activeRuntimeId.asStateFlow()

    private val _messages = MutableStateFlow<List<HermesMessage>>(emptyList())
    val messages: StateFlow<List<HermesMessage>> = _messages.asStateFlow()

    private val _activeApprovals = MutableStateFlow<List<HermesApproval>>(emptyList())
    val activeApprovals: StateFlow<List<HermesApproval>> = _activeApprovals.asStateFlow()

    private val _activeClarify = MutableStateFlow<HermesClarifyRequest?>(null)
    val activeClarify: StateFlow<HermesClarifyRequest?> = _activeClarify.asStateFlow()

    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private var reconnectJob: Job? = null
    private var autoReconnectEnabled = true
    private var reconnectAttempt = 0

    init {
        scope.launch {
            gatewayClient.events.collect { event ->
                handleGatewayEvent(event)
            }
        }

        scope.launch {
            gatewayClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        // Re-resume session if we had an active durable session
                        val durable = _activeDurableId.value
                        if (durable != null) {
                            try {
                                val resumeRes = gatewayClient.resumeSession(durable)
                                _activeRuntimeId.value = resumeRes.runtimeId
                            } catch (_: Exception) {
                            }
                        }
                    }
                    is ConnectionState.AuthExpired -> {
                        autoReconnectEnabled = false
                        reconnectJob?.cancel()
                    }
                    is ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        if (autoReconnectEnabled && _activeConnection.value != null) {
                            scheduleReconnect()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val baseDelay = min(30_000L, (1000L * (1 shl min(reconnectAttempt, 5))))
            val jitter = Random.nextLong(0, 1000)
            val totalDelay = baseDelay + jitter
            reconnectAttempt++

            delay(totalDelay)
            val connection = _activeConnection.value ?: return@launch
            try {
                connectInternal(connection)
            } catch (_: Exception) {
                // Retry will be scheduled on next disconnect/failure
            }
        }
    }

    suspend fun checkStatus(connection: HermesConnection): Result<HermesServerStatus> {
        val result = restClient.getStatus(connection.baseUrl, connection.allowCleartext)
        if (result.isSuccess) {
            _serverStatus.value = result.getOrNull()
        }
        return result
    }

    suspend fun connect(connection: HermesConnection): Result<Unit> {
        autoReconnectEnabled = true
        _activeConnection.value = connection
        return connectInternal(connection)
    }

    private suspend fun connectInternal(connection: HermesConnection): Result<Unit> {
        return try {
            val statusResult = restClient.getStatus(connection.baseUrl, connection.allowCleartext)
            val status = statusResult.getOrNull() ?: HermesServerStatus()
            _serverStatus.value = status

            var ticket: String? = null
            if (status.authRequired) {
                var tokens = tokenVault.getTokens(connection.id)
                    ?: return Result.failure(IllegalStateException("Authentication required for this server"))

                val nowSeconds = System.currentTimeMillis() / 1000
                val isExpiring = tokens.expiresAt > 0 && nowSeconds >= (tokens.expiresAt - 60)

                if (isExpiring && tokens.refreshToken.isNotEmpty()) {
                    val refreshRes = restClient.refreshNativeToken(
                        baseUrl = connection.baseUrl,
                        refreshToken = tokens.refreshToken,
                        provider = tokens.provider,
                        allowCleartext = connection.allowCleartext
                    )
                    if (refreshRes.isSuccess) {
                        val newTokens = refreshRes.getOrThrow()
                        tokenVault.saveTokens(connection.id, newTokens)
                        tokens = newTokens
                    } else {
                        val err = refreshRes.exceptionOrNull()
                        val errMsg = err?.message ?: ""
                        if (errMsg.contains("401") || errMsg.contains("session_expired") || errMsg.contains("invalid_grant")) {
                            tokenVault.clearTokens(connection.id)
                            gatewayClient.setAuthExpired("Session expired. Please sign in again.")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                    }
                }

                var ticketResult = restClient.mintWsTicket(
                    baseUrl = connection.baseUrl,
                    accessToken = tokens.accessToken,
                    allowCleartext = connection.allowCleartext
                )

                if (ticketResult.isFailure) {
                    val err = ticketResult.exceptionOrNull()
                    val errMsg = err?.message ?: ""
                    if (errMsg.contains("401") && tokens.refreshToken.isNotEmpty()) {
                        val refreshRes = restClient.refreshNativeToken(
                            baseUrl = connection.baseUrl,
                            refreshToken = tokens.refreshToken,
                            provider = tokens.provider,
                            allowCleartext = connection.allowCleartext
                        )
                        if (refreshRes.isSuccess) {
                            val newTokens = refreshRes.getOrThrow()
                            tokenVault.saveTokens(connection.id, newTokens)
                            tokens = newTokens
                            ticketResult = restClient.mintWsTicket(
                                baseUrl = connection.baseUrl,
                                accessToken = tokens.accessToken,
                                allowCleartext = connection.allowCleartext
                            )
                        } else {
                            tokenVault.clearTokens(connection.id)
                            gatewayClient.setAuthExpired("Session expired. Please sign in again.")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                    }

                    if (ticketResult.isFailure) {
                        val finalErr = ticketResult.exceptionOrNull()
                        if (finalErr?.message?.contains("401") == true) {
                            tokenVault.clearTokens(connection.id)
                            gatewayClient.setAuthExpired("Session expired. Please sign in again.")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                        return Result.failure(
                            finalErr ?: IOException("Failed to mint WebSocket ticket")
                        )
                    }
                }
                ticket = ticketResult.getOrNull()
            }

            val wsUrl = convertHttpToWsUrl(connection.baseUrl)
            gatewayClient.connect(
                wsUrl = wsUrl,
                ticket = ticket,
                allowCleartext = connection.allowCleartext
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        gatewayClient.disconnect()
        _activeConnection.value = null
        _activeDurableId.value = null
        _activeRuntimeId.value = null
        _messages.value = emptyList()
        _activeApprovals.value = emptyList()
        _activeClarify.value = null
        _isExecuting.value = false
    }

    private fun convertHttpToWsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "ws://$trimmed"
        }
        return when {
            wsBase.endsWith("/api/ws") -> wsBase
            wsBase.endsWith("/ws") -> wsBase.removeSuffix("/ws") + "/api/ws"
            else -> "$wsBase/api/ws"
        }
    }

    suspend fun listSessions(limit: Int = 200): List<SessionSummary> {
        return gatewayClient.listSessions(limit)
    }

    suspend fun startNewSession(): CreateSessionResult {
        val result = gatewayClient.createSession(source = "android")
        _activeDurableId.value = result.durableId
        _activeRuntimeId.value = result.runtimeId
        _messages.value = emptyList()
        _activeApprovals.value = emptyList()
        _activeClarify.value = null
        _isExecuting.value = false
        return result
    }

    suspend fun openSession(durableId: DurableSessionId): ResumeSessionResult {
        val result = gatewayClient.resumeSession(durableId, source = "android")
        _activeDurableId.value = result.durableId
        _activeRuntimeId.value = result.runtimeId
        _messages.value = emptyList()
        _activeApprovals.value = emptyList()
        _activeClarify.value = null
        _isExecuting.value = false
        return result
    }

    suspend fun sendUserPrompt(text: String): PromptSubmitResult {
        val runtimeId = _activeRuntimeId.value
            ?: throw IllegalStateException("No active runtime session")

        val userMessage = HermesMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text,
            isStreaming = false
        )
        _messages.value = _messages.value + userMessage
        _isExecuting.value = true

        return try {
            val result = gatewayClient.submitPrompt(runtimeId, text)
            result
        } catch (e: Exception) {
            _isExecuting.value = false
            throw e
        }
    }

    suspend fun interruptSession(): Boolean {
        val runtimeId = _activeRuntimeId.value ?: return false
        val success = gatewayClient.interruptSession(runtimeId)
        if (success) {
            _isExecuting.value = false
        }
        return success
    }

    suspend fun respondApproval(requestId: String, choice: String, all: Boolean = false): Boolean {
        val sessionKey = _activeRuntimeId.value?.value ?: _activeDurableId.value?.value ?: ""
        val success = gatewayClient.respondApproval(sessionKey, requestId, choice, all)
        if (success) {
            _activeApprovals.value = _activeApprovals.value.filterNot { it.requestId == requestId }
        }
        return success
    }

    suspend fun respondClarify(requestId: String, answer: String, questionId: String? = null): Boolean {
        val success = gatewayClient.respondClarify(requestId, answer, questionId)
        if (success) {
            _activeClarify.value = null
        }
        return success
    }

    suspend fun respondSudo(requestId: String, password: String): Boolean {
        val success = gatewayClient.respondSudo(requestId, password)
        if (success) {
            _activeClarify.value = null
        }
        return success
    }

    suspend fun respondSecret(requestId: String, value: String): Boolean {
        val success = gatewayClient.respondSecret(requestId, value)
        if (success) {
            _activeClarify.value = null
        }
        return success
    }

    private fun handleGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.MessageStartEvent -> {
                _isExecuting.value = true
                val existing = _messages.value.find { it.id == event.messageId }
                if (existing == null) {
                    val role = if (event.role.equals("user", ignoreCase = true)) MessageRole.USER else MessageRole.ASSISTANT
                    val newMsg = HermesMessage(
                        id = event.messageId,
                        role = role,
                        content = "",
                        isStreaming = true
                    )
                    _messages.value = _messages.value + newMsg
                }
            }

            is GatewayEvent.MessageDeltaEvent -> {
                _isExecuting.value = true
                val list = _messages.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    val current = list[idx]
                    list[idx] = current.copy(
                        content = current.content + event.delta,
                        isStreaming = true
                    )
                    _messages.value = list
                } else {
                    // Message wasn't explicitly started, create streaming assistant message
                    val newMsg = HermesMessage(
                        id = event.messageId,
                        role = MessageRole.ASSISTANT,
                        content = event.delta,
                        isStreaming = true
                    )
                    _messages.value = list + newMsg
                }
            }

            is GatewayEvent.MessageInterimEvent -> {
                val list = _messages.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(content = event.content, isStreaming = true)
                    _messages.value = list
                }
            }

            is GatewayEvent.MessageCompleteEvent -> {
                _isExecuting.value = false
                val list = _messages.value.toMutableList()
                val idx = list.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(
                        content = if (event.content.isNotEmpty()) event.content else list[idx].content,
                        isStreaming = false
                    )
                    _messages.value = list
                } else if (event.content.isNotEmpty()) {
                    val newMsg = HermesMessage(
                        id = event.messageId,
                        role = MessageRole.ASSISTANT,
                        content = event.content,
                        isStreaming = false
                    )
                    _messages.value = list + newMsg
                }
            }

            is GatewayEvent.ThinkingDeltaEvent -> {
                val list = _messages.value.toMutableList()
                val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (idx >= 0) {
                    val current = list[idx]
                    list[idx] = current.copy(
                        thinking = (current.thinking ?: "") + event.delta
                    )
                    _messages.value = list
                }
            }

            is GatewayEvent.ReasoningDeltaEvent -> {
                val list = _messages.value.toMutableList()
                val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (idx >= 0) {
                    val current = list[idx]
                    list[idx] = current.copy(
                        thinking = (current.thinking ?: "") + event.delta
                    )
                    _messages.value = list
                }
            }

            is GatewayEvent.ReasoningAvailableEvent -> {
                val list = _messages.value.toMutableList()
                val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT }
                if (idx >= 0) {
                    val current = list[idx]
                    list[idx] = current.copy(thinking = event.reasoning)
                    _messages.value = list
                }
            }

            is GatewayEvent.ToolStartEvent -> {
                val tool = ToolActivity(
                    id = event.toolId,
                    name = event.name,
                    status = "running"
                )
                attachToolToLastAssistantMessage(tool)
            }

            is GatewayEvent.ToolProgressEvent -> {
                updateToolInLastAssistantMessage(event.toolId) { it.copy(progress = event.progress) }
            }

            is GatewayEvent.ToolGeneratingEvent -> {
                updateToolInLastAssistantMessage(event.toolId) { it.copy(status = "generating") }
            }

            is GatewayEvent.ToolCompleteEvent -> {
                updateToolInLastAssistantMessage(event.toolId) {
                    it.copy(
                        status = if (event.isError) "failed" else "completed",
                        result = event.result,
                        isError = event.isError
                    )
                }
            }

            is GatewayEvent.ApprovalRequestEvent -> {
                val approval = HermesApproval(
                    requestId = event.requestId,
                    command = event.command,
                    description = event.description,
                    choices = event.choices
                )
                _activeApprovals.value = _activeApprovals.value.filterNot { it.requestId == event.requestId } + approval
            }

            is GatewayEvent.ClarifyRequestEvent -> {
                _activeClarify.value = HermesClarifyRequest(
                    requestId = event.requestId,
                    questionId = event.questionId,
                    question = event.question,
                    promptType = ClarifyType.CLARIFY
                )
            }

            is GatewayEvent.SudoRequestEvent -> {
                _activeClarify.value = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SUDO
                )
            }

            is GatewayEvent.SecretRequestEvent -> {
                _activeClarify.value = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SECRET
                )
            }

            is GatewayEvent.SessionInfoEvent -> {
                _sessionInfo.value = event.info
            }

            is GatewayEvent.ErrorEvent -> {
                _isExecuting.value = false
            }

            else -> {}
        }
    }

    private fun attachToolToLastAssistantMessage(tool: ToolActivity) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (idx >= 0) {
            val current = list[idx]
            val updatedTools = current.tools.filterNot { it.id == tool.id } + tool
            list[idx] = current.copy(tools = updatedTools)
            _messages.value = list
        } else {
            // Create a message containing this tool
            val newMsg = HermesMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = "",
                tools = listOf(tool),
                isStreaming = true
            )
            _messages.value = list + newMsg
        }
    }

    private fun updateToolInLastAssistantMessage(toolId: String, transform: (ToolActivity) -> ToolActivity) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { msg -> msg.tools.any { it.id == toolId } }
        if (idx >= 0) {
            val current = list[idx]
            val updatedTools = current.tools.map { if (it.id == toolId) transform(it) else it }
            list[idx] = current.copy(tools = updatedTools)
            _messages.value = list
        }
    }
}
