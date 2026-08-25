package app.hermes.mobile.core.repository

import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.runtime.HermesHostRuntime
import app.hermes.mobile.core.storage.*
import app.hermes.mobile.core.sync.UnifiedContextBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private sealed class PersistCommand {
    data class InsertOrUpdate(val sessionId: String, val message: UnifiedMessage) : PersistCommand()
    data class UpdateContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        val thinking: String?,
        val toolsJson: String?
    ) : PersistCommand()
}

class UnifiedSessionRepository(
    val connectionManager: HermesConnectionManager,
    val sessionDao: UnifiedSessionDao,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val MAX_CACHED_SESSIONS = 10
    }

    val sessions: StateFlow<List<UnifiedSession>> = sessionDao.getUnifiedSessionsSummaryFlow()
        .map { list ->
            list.map { summary ->
                UnifiedSession(
                    id = UnifiedSessionId(summary.id),
                    title = summary.title,
                    activeHostId = HermesHostId(summary.activeHostId),
                    createdAt = summary.createdAt,
                    updatedAt = summary.updatedAt,
                    bindings = emptyMap(),
                    timeline = emptyList(),
                    messageCount = summary.messageCount,
                    lastMessagePreview = summary.lastMessagePreview
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Scoped tool to message attribution mapping (toolId -> messageId)
    private val toolToMessageMap = ConcurrentHashMap<String, String>()

    // Per-session approval requests state
    private val sessionApprovalsState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<List<HostAttributedApproval>>>()

    // Per-session clarify queue state (FIFO queue of requests)
    private val sessionClarifyQueueState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<List<HostAttributedClarify>>>()
    private val sessionActiveClarifyFlows = ConcurrentHashMap<UnifiedSessionId, StateFlow<HostAttributedClarify?>>()

    // Global flows (for backward compatibility and system-level monitoring)
    private val _activeApprovals = MutableStateFlow<List<HostAttributedApproval>>(emptyList())
    val activeApprovals: StateFlow<List<HostAttributedApproval>> = _activeApprovals.asStateFlow()

    private val _activeClarify = MutableStateFlow<HostAttributedClarify?>(null)
    val activeClarify: StateFlow<HostAttributedClarify?> = _activeClarify.asStateFlow()

    private val _hasActiveTasks = MutableStateFlow(false)
    val hasActiveTasks: StateFlow<Boolean> = _hasActiveTasks.asStateFlow()

    // Mapping from (hostId, runtimeSessionId) to sessionId
    private val runtimeToSessionMap = ConcurrentHashMap<Pair<HermesHostId, String>, UnifiedSessionId>()

    // In-memory active session messages cache for reactive streaming updates
    private val sessionMessagesState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<List<UnifiedMessage>>>()

    // Independent per-(session, host) execution state
    private val hostExecutingState = ConcurrentHashMap<Pair<UnifiedSessionId, HermesHostId>, MutableStateFlow<Boolean>>()
    private val sessionExecutingState = ConcurrentHashMap<UnifiedSessionId, MutableStateFlow<Boolean>>()

    // Per-(sessionId, hostId) mutex to avoid concurrent native session creation races (DATA-06)
    private val sessionHostMutexes = ConcurrentHashMap<Pair<UnifiedSessionId, HermesHostId>, Mutex>()

    // Serialized FIFO channel for Room persistence and stream batching (DATA-04)
    private val persistChannel = Channel<PersistCommand>(Channel.UNLIMITED)
    private val lastDbPersistTimestamp = ConcurrentHashMap<String, Long>()
    private val scheduledFlushJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            connectionManager.allEvents.collect { hostEvent ->
                handleHostGatewayEvent(hostEvent)
            }
        }

        // Dedicated sequential consumer for DB persistence ensuring strict FIFO order
        scope.launch {
            for (cmd in persistChannel) {
                try {
                    when (cmd) {
                        is PersistCommand.InsertOrUpdate -> {
                            sessionDao.insertOrUpdateMessage(cmd.message.toEntity(cmd.sessionId))
                        }
                        is PersistCommand.UpdateContent -> {
                            sessionDao.updateMessageContent(
                                messageId = cmd.messageId,
                                content = cmd.content,
                                isStreaming = cmd.isStreaming,
                                thinking = cmd.thinking,
                                toolsJson = cmd.toolsJson
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Ignore background persist errors gracefully
                }
            }
        }
    }

    private fun getSessionHostMutex(sessionId: UnifiedSessionId, hostId: HermesHostId): Mutex {
        return sessionHostMutexes.computeIfAbsent(Pair(sessionId, hostId)) { Mutex() }
    }

    private fun pruneIdleSessionCaches() {
        if (sessionMessagesState.size > MAX_CACHED_SESSIONS) {
            val idleSessionIds = sessionMessagesState.keys.filter { sid ->
                val isExec = sessionExecutingState[sid]?.value ?: false
                !isExec
            }
            for (sid in idleSessionIds) {
                if (sessionMessagesState.size <= MAX_CACHED_SESSIONS) break
                releaseSession(sid)
            }
        }
    }

    fun releaseSession(sessionId: UnifiedSessionId) {
        val executing = sessionExecutingState[sessionId]?.value ?: false
        if (executing) return

        val messages = sessionMessagesState.remove(sessionId)?.value ?: emptyList()
        for (m in messages) {
            for (t in m.tools) {
                toolToMessageMap.remove(t.id)
            }
        }
        sessionExecutingState.remove(sessionId)
        sessionApprovalsState.remove(sessionId)
        sessionClarifyQueueState.remove(sessionId)
        sessionActiveClarifyFlows.remove(sessionId)
        hostExecutingState.entries.removeIf { it.key.first == sessionId }
        sessionHostMutexes.entries.removeIf { it.key.first == sessionId }
        _hasActiveTasks.update { hostExecutingState.values.any { it.value } }
    }

    fun getSessionMessages(sessionId: UnifiedSessionId): StateFlow<List<UnifiedMessage>> {
        pruneIdleSessionCaches()
        return sessionMessagesState.computeIfAbsent(sessionId) {
            val flow = MutableStateFlow<List<UnifiedMessage>>(emptyList())
            scope.launch {
                val details = sessionDao.getSessionWithDetails(sessionId.value)
                if (details != null) {
                    flow.update { current ->
                        if (current.isEmpty()) details.messages.map { it.toDomain() } else current
                    }
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

    fun getActiveApprovals(sessionId: UnifiedSessionId): StateFlow<List<HostAttributedApproval>> {
        return sessionApprovalsState.computeIfAbsent(sessionId) {
            MutableStateFlow(emptyList())
        }.asStateFlow()
    }

    fun getActiveClarify(sessionId: UnifiedSessionId): StateFlow<HostAttributedClarify?> {
        return sessionActiveClarifyFlows.computeIfAbsent(sessionId) {
            val queueFlow = sessionClarifyQueueState.computeIfAbsent(sessionId) {
                MutableStateFlow(emptyList())
            }
            queueFlow
                .map { list -> list.firstOrNull() }
                .stateIn(scope, SharingStarted.Eagerly, queueFlow.value.firstOrNull())
        }
    }

    suspend fun createUnifiedSession(
        title: String = "New Session",
        initialHostId: HermesHostId? = null
    ): UnifiedSession {
        pruneIdleSessionCaches()
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
        val msgs = sessionMessagesState.remove(sessionId)?.value ?: emptyList()
        for (m in msgs) {
            for (t in m.tools) {
                toolToMessageMap.remove(t.id)
            }
        }
        sessionExecutingState.remove(sessionId)
        sessionApprovalsState.remove(sessionId)
        sessionClarifyQueueState.remove(sessionId)
        sessionActiveClarifyFlows.remove(sessionId)
        hostExecutingState.entries.removeIf { it.key.first == sessionId }
        runtimeToSessionMap.entries.removeIf { it.value == sessionId }
        sessionHostMutexes.entries.removeIf { it.key.first == sessionId }
        _hasActiveTasks.update { hostExecutingState.values.any { it.value } }
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
        val mutex = getSessionHostMutex(sessionId, targetHostId)
        return mutex.withLock {
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
                return@withLock binding
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

            binding
        }
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

        val promptToSend = text
        val contextPreamble = if (syncResult.hasNewContext && currentSession.timeline.isNotEmpty()) {
            // Include context transfer message in timeline as a visual marker
            val transferMsg = UnifiedMessage(
                id = UUID.randomUUID().toString(),
                role = MessageRole.SYSTEM,
                content = "Context synchronized with ${host.displayName}",
                hostId = targetHostId,
                source = UnifiedMessageSource.TRANSFER,
                createdAt = System.currentTimeMillis()
            )
            insertMessageToSession(sessionId, transferMsg, immediate = true)
            syncResult.contextPrompt
        } else {
            null
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
        insertMessageToSession(sessionId, userMessage, immediate = true)

        setHostExecuting(sessionId, targetHostId, true)
        sessionDao.updateBindingState(sessionId.value, targetHostId.value, BindingState.RUNNING.name)

        return try {
            val result = runtime.gatewayClient.submitPrompt(binding.runtimeSessionId, promptToSend, contextPreamble)

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
            _activeApprovals.update { current ->
                current.filterNot {
                    it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == requestId
                }
            }
            sessionApprovalsState.values.forEach { flow ->
                flow.update { current ->
                    current.filterNot {
                        it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == requestId
                    }
                }
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
            removeClarifyFromQueues(hostId, requestId)
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
            removeClarifyFromQueues(hostId, requestId)
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
            removeClarifyFromQueues(hostId, requestId)
        }
        return success
    }

    suspend fun dismissClarify(
        hostId: HermesHostId,
        requestId: String,
        promptType: ClarifyType = ClarifyType.CLARIFY,
        questionId: String? = null
    ): Boolean {
        // Do NOT send empty strings or bogus passwords/secrets across JSON-RPC.
        // The host contract does not currently support an explicit cancel RPC for modal requests.
        // Dismiss the clarify modal locally by removing it from active state and queues.
        removeClarifyFromQueues(hostId, requestId)
        return true
    }

    private fun removeClarifyFromQueues(hostId: HermesHostId, requestId: String) {
        _activeClarify.update { current ->
            if (current?.hostId == hostId && current.request.requestId == requestId) null else current
        }
        sessionClarifyQueueState.values.forEach { queueFlow ->
            queueFlow.update { list ->
                list.filterNot { it.hostId == hostId && it.request.requestId == requestId }
            }
        }
    }

    private fun insertMessageToSession(sessionId: UnifiedSessionId, message: UnifiedMessage, immediate: Boolean = true) {
        if (message.id.isBlank()) return
        val flow = sessionMessagesState.computeIfAbsent(sessionId) {
            MutableStateFlow(emptyList())
        }
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == message.id }
            if (idx >= 0) {
                current.toMutableList().apply { set(idx, message) }
            } else {
                current + message
            }
        }

        if (immediate || !message.isStreaming) {
            lastDbPersistTimestamp[message.id] = System.currentTimeMillis()
            persistChannel.trySend(PersistCommand.InsertOrUpdate(sessionId.value, message))
        } else {
            val now = System.currentTimeMillis()
            val last = lastDbPersistTimestamp[message.id] ?: 0L
            if (now - last >= 1000L) {
                lastDbPersistTimestamp[message.id] = now
                persistChannel.trySend(PersistCommand.InsertOrUpdate(sessionId.value, message))
            } else {
                scheduleDelayedPersist(sessionId, message)
            }
        }
    }

    private fun updateMessageInSession(
        sessionId: UnifiedSessionId,
        messageId: String,
        immediate: Boolean = false,
        transform: (UnifiedMessage) -> UnifiedMessage
    ) {
        if (messageId.isBlank()) return
        val flow = sessionMessagesState.computeIfAbsent(sessionId) {
            MutableStateFlow(emptyList())
        }
        var updatedMsg: UnifiedMessage? = null
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val updated = transform(current[idx])
                updatedMsg = updated
                current.toMutableList().apply { set(idx, updated) }
            } else {
                current
            }
        }

        val msg = updatedMsg ?: return

        if (immediate || !msg.isStreaming) {
            scheduledFlushJobs.remove(msg.id)?.cancel()
            lastDbPersistTimestamp[msg.id] = System.currentTimeMillis()
            val toolsJson = if (msg.tools.isNotEmpty()) json.encodeToString(msg.tools) else null
            persistChannel.trySend(
                PersistCommand.UpdateContent(
                    messageId = msg.id,
                    content = msg.content,
                    isStreaming = msg.isStreaming,
                    thinking = msg.thinking,
                    toolsJson = toolsJson
                )
            )
        } else {
            val now = System.currentTimeMillis()
            val last = lastDbPersistTimestamp[msg.id] ?: 0L
            if (now - last >= 1000L) {
                scheduledFlushJobs.remove(msg.id)?.cancel()
                lastDbPersistTimestamp[msg.id] = now
                val toolsJson = if (msg.tools.isNotEmpty()) json.encodeToString(msg.tools) else null
                persistChannel.trySend(
                    PersistCommand.UpdateContent(
                        messageId = msg.id,
                        content = msg.content,
                        isStreaming = msg.isStreaming,
                        thinking = msg.thinking,
                        toolsJson = toolsJson
                    )
                )
            } else {
                scheduleDelayedUpdate(sessionId, msg)
            }
        }
    }

    private fun scheduleDelayedPersist(sessionId: UnifiedSessionId, message: UnifiedMessage) {
        if (scheduledFlushJobs.containsKey(message.id)) return
        val job = scope.launch {
            try {
                delay(1000)
                scheduledFlushJobs.remove(message.id)
                lastDbPersistTimestamp[message.id] = System.currentTimeMillis()
                val latest = sessionMessagesState[sessionId]?.value?.find { it.id == message.id } ?: message
                persistChannel.trySend(PersistCommand.InsertOrUpdate(sessionId.value, latest))
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Ignored on cancellation
            }
        }
        scheduledFlushJobs[message.id] = job
    }

    private fun scheduleDelayedUpdate(sessionId: UnifiedSessionId, message: UnifiedMessage) {
        if (scheduledFlushJobs.containsKey(message.id)) return
        val job = scope.launch {
            try {
                delay(1000)
                scheduledFlushJobs.remove(message.id)
                lastDbPersistTimestamp[message.id] = System.currentTimeMillis()
                val latest = sessionMessagesState[sessionId]?.value?.find { it.id == message.id } ?: message
                val toolsJson = if (latest.tools.isNotEmpty()) json.encodeToString(latest.tools) else null
                persistChannel.trySend(
                    PersistCommand.UpdateContent(
                        messageId = latest.id,
                        content = latest.content,
                        isStreaming = latest.isStreaming,
                        thinking = latest.thinking,
                        toolsJson = toolsJson
                    )
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Ignored on cancellation
            }
        }
        scheduledFlushJobs[message.id] = job
    }

    private fun setHostExecuting(sessionId: UnifiedSessionId, hostId: HermesHostId, executing: Boolean) {
        hostExecutingState.computeIfAbsent(Pair(sessionId, hostId)) {
            MutableStateFlow(false)
        }.update { executing }

        // Derive aggregate executing state for this session
        val isAnyHostExecuting = hostExecutingState.entries
            .filter { it.key.first == sessionId }
            .any { it.value.value }
        sessionExecutingState.computeIfAbsent(sessionId) {
            MutableStateFlow(false)
        }.update { isAnyHostExecuting }
        
        _hasActiveTasks.update { hostExecutingState.values.any { it.value } }
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

        return null
    }

    private fun handleHostGatewayEvent(hostEvent: HostGatewayEvent) {
        val hostId = hostEvent.hostId
        val event = hostEvent.event
        val hostName = connectionManager.hosts.value.find { it.id == hostId }?.displayName ?: hostId.value

        when (event) {
            is GatewayEvent.MessageStartEvent -> {
                if (event.messageId.isBlank()) return
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
                    insertMessageToSession(sessionId, newMsg, immediate = true)
                }
            }

            is GatewayEvent.MessageDeltaEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                setHostExecuting(sessionId, hostId, true)
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val idx = flow.value.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    updateMessageInSession(sessionId, event.messageId, immediate = false) {
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
                    insertMessageToSession(sessionId, newMsg, immediate = false)
                }
            }

            is GatewayEvent.MessageInterimEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                updateMessageInSession(sessionId, event.messageId, immediate = false) {
                    it.copy(content = event.content, isStreaming = true)
                }
            }

            is GatewayEvent.MessageCompleteEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                setHostExecuting(sessionId, hostId, false)
                // Clean up tool mappings associated with this message upon completion
                toolToMessageMap.entries.removeIf { it.value == event.messageId }
                scope.launch {
                    sessionDao.updateBindingState(sessionId.value, hostId.value, BindingState.READY.name)
                }
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val idx = flow.value.indexOfFirst { it.id == event.messageId }
                if (idx >= 0) {
                    updateMessageInSession(sessionId, event.messageId, immediate = true) {
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
                    insertMessageToSession(sessionId, newMsg, immediate = true)
                }
            }

            is GatewayEvent.ThinkingDeltaEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = if (event.messageId.isNotBlank()) {
                    flow.value.find { it.id == event.messageId && (it.hostId == hostId || it.hostId == null) }
                } else {
                    // Fallback for events without messageId: bind to last assistant for this host
                    flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id, immediate = false) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningDeltaEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = if (event.messageId.isNotBlank()) {
                    flow.value.find { it.id == event.messageId && (it.hostId == hostId || it.hostId == null) }
                } else {
                    // Fallback for events without messageId: bind to last assistant for this host
                    flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id, immediate = false) {
                        it.copy(thinking = (it.thinking ?: "") + event.delta)
                    }
                }
            }

            is GatewayEvent.ReasoningAvailableEvent -> {
                if (event.messageId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = event.messageId) ?: return
                val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                val targetAssistant = if (event.messageId.isNotBlank()) {
                    flow.value.find { it.id == event.messageId && (it.hostId == hostId || it.hostId == null) }
                } else {
                    // Fallback for events without messageId: bind to last assistant for this host
                    flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                }
                if (targetAssistant != null) {
                    updateMessageInSession(sessionId, targetAssistant.id, immediate = true) {
                        it.copy(thinking = event.reasoning)
                    }
                }
            }

            is GatewayEvent.ToolStartEvent -> {
                if (event.toolId.isBlank()) return
                val explicitMessageId = (event.rawPayload["payload"] as? kotlinx.serialization.json.JsonObject)?.get("message_id")?.let {
                    if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
                }
                val sessionId = findSessionForEvent(hostId, event.sessionId, messageId = explicitMessageId) ?: return
                val tool = ToolActivity(id = event.toolId, name = event.name, status = "running")
                attachToolToSessionMessage(sessionId, hostId, tool, explicitMessageId = explicitMessageId)
            }

            is GatewayEvent.ToolProgressEvent -> {
                if (event.toolId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, toolId = event.toolId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(progress = event.progress) }
            }

            is GatewayEvent.ToolGeneratingEvent -> {
                if (event.toolId.isBlank()) return
                val sessionId = findSessionForEvent(hostId, event.sessionId, toolId = event.toolId) ?: return
                updateToolInSessionMessage(sessionId, event.toolId) { it.copy(status = "generating") }
            }

            is GatewayEvent.ToolCompleteEvent -> {
                if (event.toolId.isBlank()) return
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
                if (event.requestId.isBlank()) return
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
                val sessionId = findSessionForEvent(hostId, runtimeSessionIdVal)
                if (sessionId != null) {
                    val sessionFlow = sessionApprovalsState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                    sessionFlow.update { current ->
                        current.filterNot {
                            it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == event.requestId
                        } + attributed
                    }
                }
                _activeApprovals.update { current ->
                    current.filterNot {
                        it.hostId == hostId && it.runtimeSessionId == runtimeSessionId && it.approval.requestId == event.requestId
                    } + attributed
                }
            }

            is GatewayEvent.ClarifyRequestEvent -> {
                if (event.requestId.isBlank()) return
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    questionId = event.questionId,
                    question = event.question,
                    promptType = ClarifyType.CLARIFY
                )
                val attributed = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
                val sessionId = findSessionForEvent(hostId, event.sessionId)
                if (sessionId != null) {
                    val queue = sessionClarifyQueueState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                    queue.update { current ->
                        current.filterNot { it.hostId == hostId && it.request.requestId == event.requestId } + attributed
                    }
                }
                _activeClarify.update { attributed }
            }

            is GatewayEvent.SudoRequestEvent -> {
                if (event.requestId.isBlank()) return
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SUDO
                )
                val attributed = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
                val sessionId = findSessionForEvent(hostId, event.sessionId)
                if (sessionId != null) {
                    val queue = sessionClarifyQueueState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                    queue.update { current ->
                        current.filterNot { it.hostId == hostId && it.request.requestId == event.requestId } + attributed
                    }
                }
                _activeClarify.update { attributed }
            }

            is GatewayEvent.SecretRequestEvent -> {
                if (event.requestId.isBlank()) return
                val runtimeSessionIdVal = event.sessionId
                val req = HermesClarifyRequest(
                    requestId = event.requestId,
                    question = event.question,
                    promptType = ClarifyType.SECRET
                )
                val attributed = HostAttributedClarify(
                    hostId = hostId,
                    hostDisplayName = hostName,
                    runtimeSessionId = runtimeSessionIdVal?.let { RuntimeSessionId(it) },
                    request = req
                )
                val sessionId = findSessionForEvent(hostId, event.sessionId)
                if (sessionId != null) {
                    val queue = sessionClarifyQueueState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
                    queue.update { current ->
                        current.filterNot { it.hostId == hostId && it.request.requestId == event.requestId } + attributed
                    }
                }
                _activeClarify.update { attributed }
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

    private fun attachToolToSessionMessage(
        sessionId: UnifiedSessionId,
        hostId: HermesHostId,
        tool: ToolActivity,
        explicitMessageId: String? = null
    ) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
        val targetMsg = if (!explicitMessageId.isNullOrBlank()) {
            flow.value.find { it.id == explicitMessageId && (it.hostId == hostId || it.hostId == null) }
        } else {
            // Strict attribution to the currently streaming assistant message for this host, or the last assistant message for this host
            flow.value.lastOrNull { it.isStreaming && it.role == MessageRole.ASSISTANT && it.hostId == hostId }
                ?: flow.value.lastOrNull { it.role == MessageRole.ASSISTANT && it.hostId == hostId }
        }

        if (targetMsg != null) {
            toolToMessageMap[tool.id] = targetMsg.id
            updateMessageInSession(sessionId, targetMsg.id, immediate = true) {
                val updatedTools = it.tools.filterNot { t -> t.id == tool.id } + tool
                it.copy(tools = updatedTools)
            }
        } else {
            val newId = explicitMessageId?.ifBlank { null } ?: UUID.randomUUID().toString()
            toolToMessageMap[tool.id] = newId
            val newMsg = UnifiedMessage(
                id = newId,
                role = MessageRole.ASSISTANT,
                content = "",
                hostId = hostId,
                tools = listOf(tool),
                isStreaming = true
            )
            insertMessageToSession(sessionId, newMsg, immediate = true)
        }
    }

    private fun updateToolInSessionMessage(
        sessionId: UnifiedSessionId,
        toolId: String,
        transform: (ToolActivity) -> ToolActivity
    ) {
        val flow = sessionMessagesState.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
        val boundMessageId = toolToMessageMap[toolId]
        val targetMsg = if (boundMessageId != null) {
            flow.value.find { it.id == boundMessageId }
        } else {
            flow.value.lastOrNull { msg -> msg.tools.any { it.id == toolId } }
        }

        if (targetMsg != null) {
            updateMessageInSession(sessionId, targetMsg.id, immediate = true) { msg ->
                val updatedTools = msg.tools.map { if (it.id == toolId) transform(it) else it }
                msg.copy(tools = updatedTools)
            }
        }
    }

    private fun UnifiedSessionWithDetails.toDomain(): UnifiedSession {
        val bindingsMap = bindings.associate {
            HermesHostId(it.hostId) to it.toDomain()
        }
        val timelineList = messages
            .sortedWith(compareBy<UnifiedMessageEntity> { it.createdAt }.thenBy { it.id })
            .map { it.toDomain() }
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
