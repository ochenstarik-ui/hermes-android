package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.runtime.HermesConnectionManager
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

    // Mapping from runtimeSessionId to (sessionId, hostId)
    private val runtimeToSessionMap = ConcurrentHashMap<String, Pair<UnifiedSessionId, HermesHostId>>()

    // In-memory active session messages cache for reactive streaming updates
    private val sessionMessagesState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<List<UnifiedMessage>>>()
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
            ?: HermesHostId("default")

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
    }

    suspend fun switchSessionActiveHost(sessionId: UnifiedSessionId, targetHostId: HermesHostId) {
        sessionDao.updateActiveHost(sessionId.value, targetHostId.value, System.currentTimeMillis())
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

        // Get or create native session binding for this host
        var binding = currentSession.bindings[targetHostId]
        if (binding == null || binding.runtimeSessionId.value.isEmpty()) {
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
        }

        runtimeToSessionMap[binding.runtimeSessionId.value] = Pair(sessionId, targetHostId)

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

        // Update binding sync status
        sessionDao.updateBindingSync(
            sessionId = sessionId.value,
            hostId = targetHostId.value,
            syncedThroughMessageId = syncResult.latestSyncedMessageId,
            syncedAt = System.currentTimeMillis(),
            state = BindingState.RUNNING.name
        )

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

        setExecuting(sessionId, true)

        return try {
            val result = runtime.gatewayClient.submitPrompt(binding.runtimeSessionId, promptToSend)
            result.turnId ?: userMessage.id
        } catch (e: Exception) {
            setExecuting(sessionId, false)
            sessionDao.updateBindingState(sessionId.value, targetHostId.value, BindingState.ERROR.name)
            throw e
        }
    }

    suspend fun interruptSession(sessionId: UnifiedSessionId) {
        val details = sessionDao.getSessionWithDetails(sessionId.value) ?: return
        for (binding in details.bindings) {
            val runtime = connectionManager.getRuntime(HermesHostId(binding.hostId))
            if (runtime != null && binding.runtimeSessionId.isNotEmpty()) {
                try {
                    runtime.gatewayClient.interruptSession(RuntimeSessionId(binding.runtimeSessionId))
                } catch (_: Exception) {
                }
            }
        }
        setExecuting(sessionId, false)
    }

    suspend fun respondApproval(
        hostId: HermesHostId,
        requestId: String,
        choice: String,
        all: Boolean = false
    ): Boolean {
        val runtime = connectionManager.getRuntime(hostId) ?: return false
        val approval = _activeApprovals.value.find { it.hostId == hostId && it.approval.requestId == requestId }
        val sessionKey = "" // Gateway client handles request_id
        val success = try {
            runtime.gatewayClient.respondApproval(sessionKey, requestId, choice, all)
        } catch (_: Exception) {
            true
        }
        if (success) {
            _activeApprovals.value = _activeApprovals.value.filterNot {
                it.hostId == hostId && it.approval.requestId == requestId
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
        val success = runtime.gatewayClient.respondClarify(requestId, answer, questionId)
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
        val success = runtime.gatewayClient.respondSudo(requestId, password)
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
        val success = runtime.gatewayClient.respondSecret(requestId, secret)
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

    private fun setExecuting(sessionId: UnifiedSessionId, executing: Boolean) {
        val flow = sessionExecutingState.computeIfAbsent(sessionId) {
            MutableStateFlow(false)
        }
        flow.value = executing
    }

    private fun findSessionForHost(hostId: HermesHostId): UnifiedSessionId? {
        for ((sessionId, flow) in sessionExecutingState) {
            if (flow.value) {
                return sessionId
            }
        }
        // Fall back to active session, open session cache, or first known session
        return sessions.value.find { it.activeHostId == hostId }?.id
            ?: sessionMessagesState.keys.firstOrNull()
            ?: sessions.value.firstOrNull()?.id
    }

    private fun findSessionForMessage(messageId: String, hostId: HermesHostId): UnifiedSessionId? {
        for ((sessionId, flow) in sessionMessagesState) {
            if (flow.value.any { it.id == messageId }) {
                return sessionId
            }
        }
        return findSessionForHost(hostId)
    }

    private fun handleHostGatewayEvent(hostEvent: HostGatewayEvent) {
        val hostId = hostEvent.hostId
        val event = hostEvent.event
        val hostName = connectionManager.hosts.value.find { it.id == hostId }?.displayName ?: hostId.value

        when (event) {
            is GatewayEvent.MessageStartEvent -> {
                val sessionId = findSessionForMessage(event.messageId, hostId) ?: return
                setExecuting(sessionId, true)
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
                val sessionId = findSessionForMessage(event.messageId, hostId) ?: return
                setExecuting(sessionId, true)
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
                val sessionId = findSessionForMessage(event.messageId, hostId) ?: return
                updateMessageInSession(sessionId, event.messageId) {
                    it.copy(content = event.content, isStreaming = true)
                }
            }

            is GatewayEvent.MessageCompleteEvent -> {
                val sessionId = findSessionForMessage(event.messageId, hostId) ?: return
                setExecuting(sessionId, false)
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
                val sessionId = findSessionForHost(hostId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val lastAssistant = flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                if (lastAssistant != null) {
                    updateMessageInSession(sessionId, lastAssistant.id) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningDeltaEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val lastAssistant = flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                if (lastAssistant != null) {
                    updateMessageInSession(sessionId, lastAssistant.id) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningAvailableEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val lastAssistant = flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                if (lastAssistant != null) {
                    updateMessageInSession(sessionId, lastAssistant.id) {
                        it.copy(thinking = event.reasoning)
                    }
                }
            }

            is GatewayEvent.ToolStartEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                val tool = ToolActivity(id = event.toolId, name = event.name, status = "running")
                attachToolToSessionMessage(sessionId, hostId, tool)
            }

            is GatewayEvent.ToolProgressEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(progress = event.progress) }
            }

            is GatewayEvent.ToolGeneratingEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(status = "generating") }
            }

            is GatewayEvent.ToolCompleteEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) {
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
                val attributed = HostAttributedApproval(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    approval = approval
                )
                _activeApprovals.value = _activeApprovals.value.filterNot {
                    it.hostId == hostId && it.approval.requestId == event.requestId
                } + attributed
            }

            is GatewayEvent.ClarifyRequestEvent -> {
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    questionId = event.questionId,
                    question = event.question,
                    promptType = ClarifyType.CLARIFY
                )
                _activeClarify.value = HostAttributedClarify(hostId, hostName, req)
            }

            is GatewayEvent.SudoRequestEvent -> {
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SUDO
                )
                _activeClarify.value = HostAttributedClarify(hostId, hostName, req)
            }

            is GatewayEvent.SecretRequestEvent -> {
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SECRET
                )
                _activeClarify.value = HostAttributedClarify(hostId, hostName, req)
            }

            is GatewayEvent.ErrorEvent -> {
                val sessionId = findSessionForHost(hostId) ?: return
                setExecuting(sessionId, false)
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
        sessionId: UnifiedSessionId?,
        toolId: String,
        transform: (ToolActivity) -> ToolActivity
    ) {
        val targetSessionId = sessionId?.takeIf { sId ->
            sessionMessagesState[sId]?.value?.any { msg -> msg.tools.any { it.id == toolId } } == true
        } ?: sessionMessagesState.entries.firstOrNull { (_, flow) ->
            flow.value.any { msg -> msg.tools.any { it.id == toolId } }
        }?.key ?: sessionId ?: return

        val flow = sessionMessagesState.computeIfAbsent(targetSessionId) { MutableStateFlow(emptyList()) }
        val targetMsg = flow.value.lastOrNull { msg -> msg.tools.any { it.id == toolId } }
        if (targetMsg != null) {
            updateMessageInSession(targetSessionId, targetMsg.id) { msg ->
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
