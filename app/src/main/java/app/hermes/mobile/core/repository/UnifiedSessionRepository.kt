package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.storage.*
import app.hermes.mobile.core.sync.UnifiedContextBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnifiedSessionRepository(
    val connectionManager: HermesConnectionManager,
    val sessionDao: UnifiedSessionDao,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val json = Json { ignoreUnknownKeys = true }

    val sessions: StateFlow<List<UnifiedSession>> = sessionDao.getSessionsFlow()
        .map { list ->
            list.map { entity ->
                val details = sessionDao.getSessionWithDetails(entity.id)
                details?.toDomain() ?: entity.toDomainPlaceholder()
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activeApprovals = MutableStateFlow<List<HostAttributedApproval>>(emptyList())
    val activeApprovals: StateFlow<List<HostAttributedApproval>> = _activeApprovals.asStateFlow()

    private val _activeClarify = MutableStateFlow<HostAttributedClarify?>(null)
    val activeClarify: StateFlow<HostAttributedClarify?> = _activeClarify.asStateFlow()

    // Mapping from (hostId, runtimeSessionId) to sessionId
    private val runtimeToSessionMap = ConcurrentHashMap<Pair<HermesHostId, String>, UnifiedSessionId>()

    // In-memory active session messages cache for reactive streaming updates
    private val sessionMessagesState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<List<UnifiedMessage>>>()

    // Independent per-(session, host) execution state
    private val hostExecutingState = ConcurrentHashMap<Pair<UnifiedSessionId, HermesHostId>, MutableStateFlow<Boolean>>()
    private val sessionExecutingState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<Boolean>>()

    init {
        scope.launch {
            connectionManager.allEvents.collect { hostEvent ->
                handleHostGatewayEvent(hostEvent)
            }
        }
    }

    fun getSessionMessages(sessionId: UnifiedSessionId): StateFlow<List<UnifiedMessage>> {
        return sessionMessagesState.computeIfAbsent(sessionId) {
            val flow = MutableStateFlow<List<UnifiedMessage>>(emptyList())
            scope.launch {
                val details = sessionDao.getSessionWithDetails(sessionId.value)
                if (details != null) {
                    flow.value = details.messages.map { it.toDomain() }
                }
            }
            flow
        }.asStateFlow()
    }

    fun getHostExecuting(sessionId: UnifiedSessionId, hostId: HermesHostId): StateFlow<Boolean> {
        return hostExecutingState.computeIfAbsent(Pair(sessionId, hostId)) {
            MutableStateFlow(false)
        }.asStateFlow()
    }

    fun getSessionExecuting(sessionId: UnifiedSessionId): StateFlow<Boolean> {
        return sessionExecutingState.computeIfAbsent(sessionId) {
            MutableStateFlow(false)
        }.asStateFlow()
    }

    suspend fun createUnifiedSession(
        title: String = "New Session",
        initialHostId: HermesHostId? = null
    ): UnifiedSession {
        val hostId = initialHostId ?: connectionManager.activeHostId.value
            ?: connectionManager.hosts.value.firstOrNull()?.id
            ?: throw IllegalStateException("No Hermes hosts configured. Please add a host before creating a session.")

        val sessionId = UnifiedSessionId(UUID.randomUUID().toString())
        val sessionEntity = UnifiedSessionEntity(
            id = sessionId.value,
            title = title,
            activeHostId = hostId.value,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        sessionDao.insertSession(sessionEntity)

        val session = UnifiedSession(
            id = sessionId,
            title = title,
            activeHostId = hostId,
            createdAt = sessionEntity.createdAt,
            updatedAt = sessionEntity.updatedAt,
            bindings = emptyMap(),
            timeline = emptyList()
        )

        sessionMessagesState[sessionId] = MutableStateFlow(emptyList())
        sessionExecutingState[sessionId] = MutableStateFlow(false)
        return session
    }

    suspend fun getUnifiedSession(sessionId: UnifiedSessionId): UnifiedSession? {
        val details = sessionDao.getSessionWithDetails(sessionId.value) ?: return null
        return details.toDomain()
    }

    suspend fun deleteUnifiedSession(sessionId: UnifiedSessionId) {
        sessionDao.deleteSession(sessionId.value)
        sessionMessagesState.remove(sessionId)
        sessionExecutingState.remove(sessionId)
        hostExecutingState.entries.removeIf { it.key.first == sessionId }
        runtimeToSessionMap.entries.removeIf { it.value == sessionId }
    }

    fun registerRuntimeBinding(sessionId: UnifiedSessionId, hostId: HermesHostId, runtimeSessionId: RuntimeSessionId) {
        if (runtimeSessionId.value.isNotEmpty()) {
            runtimeToSessionMap[Pair(hostId, runtimeSessionId.value)] = sessionId
        }
    }

    suspend fun switchSessionActiveHost(sessionId: UnifiedSessionId, targetHostId: HermesHostId) {
        sessionDao.updateActiveHost(sessionId.value, targetHostId.value, System.currentTimeMillis())
    }

    suspend fun ensureAttachedRuntimeSession(
        sessionId: UnifiedSessionId,
        targetHostId: HermesHostId,
        runtime: HermesHostRuntime
    ): HostSessionBinding {
        val details = sessionDao.getSessionWithDetails(sessionId.value)
        var binding = details?.bindings?.find { it.hostId == targetHostId.value }?.toDomain()

        if (binding == null || binding.durableSessionId.value.isEmpty()) {
            val createRes = runtime.gatewayClient.createSession(source = "android")
            binding = HostSessionBinding(
                hostId = targetHostId,
                durableSessionId = createRes.durableId,
                runtimeSessionId = createRes.runtimeId,
                lastAttachedAt = System.currentTimeMillis(),
                state = BindingState.READY,
                syncedThroughMessageId = null,
                syncedAt = null
            )
            sessionDao.insertOrUpdateBinding(binding.toEntity(sessionId.value))
            runtimeToSessionMap[Pair(targetHostId, createRes.runtimeId.value)] = sessionId
            return binding
        }

        // We have an existing durableSessionId.
        // Check if current runtimeSessionId is already registered and valid in memory in the current process
        val currentRuntimeId = binding.runtimeSessionId.value
        val isRegistered = currentRuntimeId.isNotEmpty() && runtimeToSessionMap.containsKey(Pair(targetHostId, currentRuntimeId))

        if (!isRegistered || binding.state == BindingState.NOT_CREATED || binding.state == BindingState.OFFLINE || binding.state == BindingState.ERROR) {
            val resumeRes = try {
                runtime.gatewayClient.resumeSession(binding.durableSessionId, source = "android")
            } catch (e: Exception) {
                if (isDefinitivelyMissingSession(e)) {
                    val createRes = runtime.gatewayClient.createSession(source = "android")
                    ResumeSessionResult(createRes.durableId, createRes.runtimeId)
                } else {
                    // For transient errors during resume (timeout, network, auth), throw without creating a new session or destroying the binding
                    throw e
                }
            }
            binding = binding.copy(
                durableSessionId = resumeRes.durableId,
                runtimeSessionId = resumeRes.runtimeId,
                lastAttachedAt = System.currentTimeMillis(),
                state = BindingState.READY
            )
            sessionDao.insertOrUpdateBinding(binding.toEntity(sessionId.value))
            runtimeToSessionMap[Pair(targetHostId, resumeRes.runtimeId.value)] = sessionId
        } else {
            runtimeToSessionMap[Pair(targetHostId, currentRuntimeId)] = sessionId
        }

        return binding
    }

    private fun isDefinitivelyMissingSession(e: Throwable): Boolean {
        if (e is JsonRpcException) {
            if (e.code == 404 || e.code == -32004) return true
            val msg = e.errorMessage.lowercase()
            if (msg.contains("not found") || msg.contains("does not exist") ||
                msg.contains("invalid session") || msg.contains("no such session") ||
                msg.contains("session destroyed") || msg.contains("unrecoverable")) {
                return true
            }
        }
        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("404") || msg.contains("session not found") || msg.contains("session does not exist")) {
            return true
        }
        return false
    }

    suspend fun sendPrompt(sessionId: UnifiedSessionId, text: String): String {
        val details = sessionDao.getSessionWithDetails(sessionId.value)
            ?: throw IllegalArgumentException("Session not found: ${sessionId.value}")
        val currentSession = details.toDomain()
        val targetHostId = currentSession.activeHostId

        val host = connectionManager.hosts.value.find { it.id == targetHostId }
            ?: throw IllegalStateException("Active host ${targetHostId.value} is not configured")

        val runtime = connectionManager.getRuntime(targetHostId)
            ?: throw IllegalStateException("Runtime not available for host ${targetHostId.value}")

        // Ensure host is connected
        if (runtime.connectionState.value !is ConnectionState.Connected) {
            val connectRes = runtime.connect()
            if (connectRes.isFailure) {
                throw IOException("Failed to connect to ${host.displayName}: ${connectRes.exceptionOrNull()?.message}")
            }
            runtime.gatewayClient.awaitGatewayReady(10_000)
        }

        // Get or attach native session binding for this host
        val binding = ensureAttachedRuntimeSession(sessionId, targetHostId, runtime)

        // Context Synchronization
        val hostsMap = connectionManager.hosts.value.associateBy { it.id }
        val syncResult = UnifiedContextBuilder.buildContextSyncPayload(
            session = currentSession,
            targetHost = host,
            allHosts = hostsMap,
            syncedThroughMessageId = binding.syncedThroughMessageId
        )

        val promptToSend = if (syncResult.hasNewContext && currentSession.timeline.isNotEmpty()) {
            // Include context transfer message in timeline as a visual marker
            val transferMsg = UnifiedMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = "Context synchronized with ${host.displayName}",
                hostId = targetHostId,
                source = UnifiedMessageSource.TRANSFER,
                createdAt = System.currentTimeMillis()
            )
            insertMessageToSession(sessionId, transferMsg)
            UnifiedContextBuilder.mergeContextWithPrompt(syncResult.contextPrompt, text)
        } else {
            text
        }

        // Insert user message to timeline
        val userMessage = UnifiedMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = text,
            hostId = null,
            source = UnifiedMessageSource.USER,
            createdAt = System.currentTimeMillis()
        )
        insertMessageToSession(sessionId, userMessage)

        setHostExecuting(sessionId, targetHostId, true)
        sessionDao.updateBindingState(sessionId.value, targetHostId.value, BindingState.RUNNING.name)

        return try {
            val result = runtime.gatewayClient.submitPrompt(binding.runtimeSessionId, promptToSend)

            // ONLY update binding sync status AFTER successful acceptance of prompt.submit!
            sessionDao.updateBindingSync(
                sessionId = sessionId.value,
                hostId = targetHostId.value,
                syncedThroughMessageId = syncResult.latestSyncedMessageId,
                syncedAt = System.currentTimeMillis(),
                state = BindingState.RUNNING.name
            )

            result.turnId ?: userMessage.id
        } catch (e: Exception) {
            setHostExecuting(sessionId, targetHostId, false)
            sessionDao.updateBindingState(sessionId.value, targetHostId.value, BindingState.ERROR.name)
            throw e
        }
    }

    suspend fun interruptHost(sessionId: UnifiedSessionId, hostId: HermesHostId): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val details = sessionDao.getSessionWithDetails(sessionId.value) ?: return false
        val binding = details.bindings.find { it.hostId == hostId.value } ?: return false

        val success = try {
            if (binding.runtimeSessionId.isNotEmpty()) {
                runtime.gatewayClient.interruptSession(RuntimeSessionId(binding.runtimeSessionId))
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        setHostExecuting(sessionId, hostId, false)
        sessionDao.updateBindingState(sessionId.value, hostId.value, BindingState.READY.name)
        return success
    }

    suspend fun interruptSession(sessionId: UnifiedSessionId, targetHostId: HermesHostId? = null) {
        val details = sessionDao.getSessionWithDetails(sessionId.value) ?: return
        val hostToInterrupt = targetHostId ?: HermesHostId(details.session.activeHostId)
        interruptHost(sessionId, hostToInterrupt)
    }

    suspend fun respondApproval(
        hostId: HermesHostId,
        runtimeSessionId: RuntimeSessionId,
        requestId: String,
        choice: String,
        all: Boolean = false
    ): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val success = try {
            runtime.gatewayClient.respondApproval(runtimeSessionId.value, requestId, choice, all)
        } catch (_: Exception) {
            false
        }
        if (success) {
            _activeApprovals.value = _activeApprovals.value.filterNot {
                it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == requestId
            }
        }
        return success
    }

    suspend fun respondClarify(
        hostId: HermesHostId,
        requestId: String,
        answer: String,
        questionId: String? = null
    ): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val success = try {
            runtime.gatewayClient.respondClarify(requestId, answer, questionId)
        } catch (_: Exception) {
            false
        }
        if (success) {
            if (_activeClarify.value?.hostId == hostId && _activeClarify.value?.request?.requestId == requestId) {
                _activeClarify.value = null
            }
        }
        return success
    }

    suspend fun respondSudo(
        hostId: HermesHostId,
        requestId: String,
        password: String
    ): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val success = try {
            runtime.gatewayClient.respondSudo(requestId, password)
        } catch (_: Exception) {
            false
        }
        if (success) {
            if (_activeClarify.value?.hostId == hostId && _activeClarify.value?.request?.requestId == requestId) {
                _activeClarify.value = null
            }
        }
        return success
    }

    suspend fun respondSecret(
        hostId: HermesHostId,
        requestId: String,
        secret: String
    ): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val success = try {
            runtime.gatewayClient.respondSecret(requestId, secret)
        } catch (_: Exception) {
            false
        }
        if (success) {
            if (_activeClarify.value?.hostId == hostId && _activeClarify.value?.request?.requestId == requestId) {
                _activeClarify.value = null
            }
        }
        return success
    }

    private fun insertMessageToSession(sessionId: UnifiedSessionId, message: UnifiedMessage) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) {
            MutableStateFlow(emptyList())
        }
        flow.value = flow.value + message

        scope.launch {
            sessionDao.insertOrUpdateMessage(message.toEntity(sessionId.value))
        }
    }

    private fun updateMessageInSession(
        sessionId: UnifiedSessionId,
        messageId: String,
        transform: (UnifiedMessage) -> UnifiedMessage
    ) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) {
            MutableStateFlow(emptyList())
        }
        val list = flow.value.toMutableList()
        val idx = list.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            val updated = transform(list[idx])
            list[idx] = updated
            flow.value = list

            scope.launch {
                val toolsJson = if (updated.tools.isNotEmpty()) json.encodeToString(updated.tools) else null
                sessionDao.updateMessageContent(
                    messageId = updated.id,
                    content = updated.content,
                    isStreaming = updated.isStreaming,
                    thinking = updated.thinking,
                    toolsJson = toolsJson
                )
            }
        }
    }

    private fun setHostExecuting(sessionId: UnifiedSessionId, hostId: HermesHostId, executing: Boolean) {
        hostExecutingState.computeIfAbsent(Pair(sessionId, hostId)) {
            MutableStateFlow(false)
        }.value = executing

        // Derive aggregate executing state for this session
        val isAnyHostExecuting = hostExecutingState.entries
            .filter { it.key.first == sessionId }
            .any { it.value.value }
        sessionExecutingState.computeIfAbsent(sessionId) {
            MutableStateFlow(false)
        }.value = isAnyHostExecuting
    }

    private fun findSessionForEvent(
        hostId: HermesHostId,
        sessionIdFromEvent: String?,
        messageId: String? = null,
        toolId: String? = null
    ): UnifiedSessionId? {
        // 1. Exact match via (hostId, runtimeSessionId) in runtimeToSessionMap
        if (!sessionIdFromEvent.isNullOrEmpty()) {
            val mapped = runtimeToSessionMap[Pair(hostId, sessionIdFromEvent)]
            if (mapped != null) {
                return mapped
            }
        }

        // 2. Exact match via messageId in active session messages
        if (!messageId.isNullOrEmpty()) {
            for ((sessionId, flow) in sessionMessagesState) {
                if (flow.value.any { it.id == messageId && (it.hostId == hostId || it.hostId == null) }) {
                    return sessionId
                }
            }
        }

        // 3. Exact match via toolId in active session messages
        if (!toolId.isNullOrEmpty()) {
            for ((sessionId, flow) in sessionMessagesState) {
                if (flow.value.any { it.tools.any { t -> t.id == toolId } }) {
                    return sessionId
                }
            }
        }

        // Strict: NO fallback to "any executing session" or "first session in list"
        return null
    }

    private fun handleHostGatewayEvent(hostEvent: HostGatewayEvent) {
        val hostId = hostEvent.hostId
        val event = hostEvent.event
        val hostName = connectionManager.hosts.value.find { it.id == hostId }?.displayName ?: hostId.value

        when (event) {
            is GatewayEvent.MessageStartEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                setHostExecuting(sessionId, hostId, true)
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val existing = flow.value.find { it.id == event.messageId }
                if (existing == null) {
                    val role = if (event.role.equals("user", ignoreCase = true)) MessageRole.USER else MessageRole.ASSISTANT
                    val newMsg = UnifiedMessage(
                        id = event.messageId,
                        role = role,
                        content = "",
                        hostId = hostId,
                        source = UnifiedMessageSource.HERMES,
                        isStreaming = true
                    )
                    insertMessageToSession(sessionId, newMsg)
                }
            }

            is GatewayEvent.MessageDeltaEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                setHostExecuting(sessionId, hostId, true)
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val idx = flow.value.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    updateMessageInSession(sessionId, event.messageId) {
                        it.copy(content = it.content + event.delta, isStreaming = true)
                    }
                } else {
                    val newMsg = UnifiedMessage(
                        id = event.messageId,
                        role = MessageRole.ASSISTANT,
                        content = event.delta,
                        hostId = hostId,
                        source = UnifiedMessageSource.HERMES,
                        isStreaming = true
                    )
                    insertMessageToSession(sessionId, newMsg)
                }
            }

            is GatewayEvent.MessageInterimEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                updateMessageInSession(sessionId, event.messageId) {
                    it.copy(content = event.content, isStreaming = true)
                }
            }

            is GatewayEvent.MessageCompleteEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                setHostExecuting(sessionId, hostId, false)
                scope.launch {
                    sessionDao.updateBindingState(sessionId.value, hostId.value, BindingState.READY.name)
                }
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val idx = flow.value.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    updateMessageInSession(sessionId, event.messageId) {
                        it.copy(
                            content = if (event.content.isNotEmpty()) event.content else it.content,
                            isStreaming = false
                        )
                    }
                } else if (event.content.isNotEmpty()) {
                    val newMsg = UnifiedMessage(
                        id = event.messageId,
                        role = MessageRole.ASSISTANT,
                        content = event.content,
                        hostId = hostId,
                        source = UnifiedMessageSource.HERMES,
                        isStreaming = false
                    )
                    insertMessageToSession(sessionId, newMsg)
                }
            }

            is GatewayEvent.ThinkingDeltaEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = flow.value.lastOrNull { (it.id == event.messageId || it.role == MessageRole.ASSISTANT) && it.hostId == hostId }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningDeltaEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = flow.value.lastOrNull { (it.id == event.messageId || it.role == MessageRole.ASSISTANT) && it.hostId == hostId }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningAvailableEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = flow.value.lastOrNull { (it.id == event.messageId || it.role == MessageRole.ASSISTANT) && it.hostId == hostId }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id) {
                        it.copy(thinking = event.reasoning)
                    }
                }
            }

            is GatewayEvent.ToolStartEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId) ?: return
                val tool = ToolActivity(id = event.toolId, name = event.name, status = "running")
                attachToolToSessionMessage(sessionId, hostId, tool)
            }

            is GatewayEvent.ToolProgressEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, toolId = event.toolId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(progress = event.progress) }
            }

            is GatewayEvent.ToolGeneratingEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, toolId = event.toolId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(status = "generating") }
            }

            is GatewayEvent.ToolCompleteEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId, toolId = event.toolId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) {
                    it.copy(
                        status = if (event.isError) "failed" else "completed",
                        result = event.result,
                        isError = event.isError
                    )
                }
            }

            is GatewayEvent.ApprovalRequestEvent -> {
                val runtimeSessionIdVal = event.sessionKey ?: event.sessionId ?: ""
                val runtimeSessionId = RuntimeSessionId(runtimeSessionIdVal)
                val approval = HermesApproval(
                    requestId = event.requestId,
                    command = event.command,
                    description = event.description,
                    choices = event.choices
                )
                val attributed = HostAttributedApproval(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionId,
                    approval = approval
                )
                _activeApprovals.value = _activeApprovals.value.filterNot {
                    it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == event.requestId
                } + attributed
            }

            is GatewayEvent.ClarifyRequestEvent -> {
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    questionId = event.questionId,
                    question = event.question,
                    promptType = ClarifyType.CLARIFY
                )
                _activeClarify.value = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
            }

            is GatewayEvent.SudoRequestEvent -> {
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SUDO
                )
                _activeClarify.value = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
            }

            is GatewayEvent.SecretRequestEvent -> {
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SECRET
                )
                _activeClarify.value = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
            }

            is GatewayEvent.ErrorEvent -> {
                val sessionId = findSessionForEvent(hostId, event.sessionId) ?: return
                setHostExecuting(sessionId, hostId, false)
                scope.launch {
                    sessionDao.updateBindingState(sessionId.value, hostId.value, BindingState.ERROR.name)
                }
            }

            else -> {}
        }
    }

    private fun attachToolToSessionMessage(sessionId: UnifiedSessionId, hostId: HermesHostId, tool: ToolActivity) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
        val lastAssistant = flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
        if (lastAssistant != null) {
            updateMessageInSession(sessionId, lastAssistant.id) {
                val updatedTools = it.tools.filterNot { t -> t.id == tool.id } + tool
                it.copy(tools = updatedTools)
            }
        } else {
            val newMsg = UnifiedMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.ASSISTANT,
                content = "",
                hostId = hostId,
                tools = listOf(tool),
                isStreaming = true
            )
            insertMessageToSession(sessionId, newMsg)
        }
    }

    private fun updateToolInSessionMessage(
        sessionId: UnifiedSessionId,
        toolId: String,
        transform: (ToolActivity) -> ToolActivity
    ) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
        val targetMsg = flow.value.lastOrNull { msg -> msg.tools.any { it.id == toolId } }
        if (targetMsg != null) {
            updateMessageInSession(sessionId, targetMsg.id) { msg ->
                val updatedTools = msg.tools.map { if (it.id == toolId) transform(it) else it }
                msg.copy(tools = updatedTools)
            }
        }
    }

    private fun UnifiedSessionWithDetails.toDomain(): UnifiedSession {
        val bindingsMap = bindings.associate {
            HermesHostId(it.hostId) to it.toDomain()
        }
        val timelineList = messages.map { it.toDomain() }
        return UnifiedSession(
            id = UnifiedSessionId(session.id),
            title = session.title,
            activeHostId = HermesHostId(session.activeHostId),
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            bindings = bindingsMap,
            timeline = timelineList
        )
    }

    private fun UnifiedSessionEntity.toDomainPlaceholder(): UnifiedSession {
        return UnifiedSession(
            id = UnifiedSessionId(id),
            title = title,
            activeHostId = HermesHostId(activeHostId),
            createdAt = createdAt,
            updatedAt = updatedAt,
            bindings = emptyMap(),
            timeline = emptyList()
        )
    }

    private fun HostBindingEntity.toDomain(): HostSessionBinding {
        val bState = try {
            BindingState.valueOf(state)
        } catch (_: Exception) {
            BindingState.NOT_CREATED
        }
        return HostSessionBinding(
            hostId = HermesHostId(hostId),
            durableSessionId = DurableSessionId(durableSessionId),
            runtimeSessionId = RuntimeSessionId(runtimeSessionId),
            lastAttachedAt = lastAttachedAt,
            state = bState,
            syncedThroughMessageId = syncedThroughMessageId,
            syncedAt = syncedAt
        )
    }

    private fun HostSessionBinding.toEntity(sessionId: String): HostBindingEntity {
        return HostBindingEntity(
            sessionId = sessionId,
            hostId = hostId.value,
            durableSessionId = durableSessionId.value,
            runtimeSessionId = runtimeSessionId.value,
            lastAttachedAt = lastAttachedAt,
            state = state.name,
            syncedThroughMessageId = syncedThroughMessageId,
            syncedAt = syncedAt
        )
    }

    private fun UnifiedMessageEntity.toDomain(): UnifiedMessage {
        val mRole = try {
            MessageRole.valueOf(role)
        } catch (_: Exception) {
            MessageRole.ASSISTANT
        }
        val mSource = try {
            UnifiedMessageSource.valueOf(source)
        } catch (_: Exception) {
            UnifiedMessageSource.HERMES
        }
        val toolList = if (!toolsJson.isNullOrBlank()) {
            try {
                json.decodeFromString<List<ToolActivity>>(toolsJson)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return UnifiedMessage(
            id = id,
            role = mRole,
            content = content,
            hostId = hostId?.let { HermesHostId(it) },
            source = mSource,
            createdAt = createdAt,
            nativeMessageId = nativeMessageId,
            thinking = thinking,
            tools = toolList,
            isStreaming = isStreaming
        )
    }

    private fun UnifiedMessage.toEntity(sessionId: String): UnifiedMessageEntity {
        val toolsString = if (tools.isNotEmpty()) json.encodeToString(tools) else null
        return UnifiedMessageEntity(
            id = id,
            sessionId = sessionId,
            role = role.name,
            content = content,
            hostId = hostId?.value,
            source = source.name,
            createdAt = createdAt,
            nativeMessageId = nativeMessageId,
            thinking = thinking,
            toolsJson = toolsString,
            isStreaming = isStreaming
        )
    }
}
