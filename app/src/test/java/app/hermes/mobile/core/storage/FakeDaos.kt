package app.hermes.mobile.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeHostDao : HostDao {
    private val storage = mutableMapOf<String, HostEntity>()
    private val flow = MutableStateFlow<List<HostEntity>>(emptyList())

    private fun updateFlow() {
        flow.value = storage.values.sortedBy { it.displayName }
    }

    override fun getHostsFlow(): Flow<List<HostEntity>> = flow

    override suspend fun getHosts(): List<HostEntity> = storage.values.sortedBy { it.displayName }

    override suspend fun getHost(hostId: String): HostEntity? = storage[hostId]

    override suspend fun insertOrUpdateHost(host: HostEntity) {
        storage[host.id] = host
        updateFlow()
    }

    override suspend fun insertHosts(hosts: List<HostEntity>) {
        for (h in hosts) storage[h.id] = h
        updateFlow()
    }

    override suspend fun deleteHost(hostId: String) {
        storage.remove(hostId)
        updateFlow()
    }

    override suspend fun updateHostStatus(hostId: String, status: String, lastSeenAt: Long) {
        val existing = storage[hostId]
        if (existing != null) {
            storage[hostId] = existing.copy(lastKnownStatus = status, lastSeenAt = lastSeenAt)
            updateFlow()
        }
    }
}

class FakeUnifiedSessionDao : UnifiedSessionDao {
    private val sessions = mutableMapOf<String, UnifiedSessionEntity>()
    private val bindings = mutableMapOf<String, MutableList<HostBindingEntity>>()
    private val messages = mutableMapOf<String, MutableList<UnifiedMessageEntity>>()
    private val _sessionsFlow = MutableSharedFlow<List<UnifiedSessionEntity>>(replay = 1)

    private val messageComparator = compareBy<UnifiedMessageEntity> { it.createdAt }.thenBy { it.id }

    init {
        _sessionsFlow.tryEmit(emptyList<UnifiedSessionEntity>())
    }

    private fun updateFlow() {
        val list = sessions.values.sortedByDescending { it.updatedAt }.map { it.copy() }
        _sessionsFlow.tryEmit(list)
    }

    override fun getSessionsFlow(): Flow<List<UnifiedSessionEntity>> = _sessionsFlow

    override suspend fun getSessions(): List<UnifiedSessionEntity> = sessions.values.sortedByDescending { it.updatedAt }

    override suspend fun getSession(sessionId: String): UnifiedSessionEntity? = sessions[sessionId]?.copy()

    override fun getSessionFlow(sessionId: String): Flow<UnifiedSessionEntity?> =
        _sessionsFlow.map { sessions[sessionId]?.copy() }

    override fun getSessionWithDetailsFlow(sessionId: String): Flow<UnifiedSessionWithDetails?> {
        return _sessionsFlow.map { _: List<UnifiedSessionEntity> -> getSessionWithDetails(sessionId) }
    }

    override suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails? {
        val s = sessions[sessionId]?.copy() ?: return null
        val b = bindings[sessionId]?.map { it.copy() } ?: emptyList()
        val m = messages[sessionId]?.sortedWith(messageComparator)?.map { it.copy() } ?: emptyList()
        return UnifiedSessionWithDetails(session = s, bindings = b, messages = m)
    }

    override suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity> {
        return messages[sessionId]?.sortedWith(messageComparator)?.map { it.copy() } ?: emptyList()
    }

    override fun getMessagesForSessionFlow(sessionId: String): Flow<List<UnifiedMessageEntity>> =
        _sessionsFlow.map { getMessagesForSession(sessionId) }

    override suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity> {
        return bindings[sessionId]?.map { it.copy() } ?: emptyList()
    }

    override fun getBindingsForSessionFlow(sessionId: String): Flow<List<HostBindingEntity>> =
        _sessionsFlow.map { getBindingsForSession(sessionId) }

    override suspend fun insertSession(session: UnifiedSessionEntity) {
        sessions[session.id] = session
        updateFlow()
    }

    override suspend fun updateSession(session: UnifiedSessionEntity) {
        sessions[session.id] = session
        updateFlow()
    }

    override suspend fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        bindings.remove(sessionId)
        messages.remove(sessionId)
        updateFlow()
    }

    override suspend fun updateSessionUpdatedAt(sessionId: String, updatedAt: Long) {
        val s = sessions[sessionId]
        if (s != null) {
            sessions[sessionId] = s.copy(updatedAt = updatedAt)
            updateFlow()
        }
    }

    override suspend fun insertOrUpdateBindingInternal(binding: HostBindingEntity) {
        val list = bindings.computeIfAbsent(binding.sessionId) { mutableListOf() }
        list.removeAll { it.hostId == binding.hostId }
        list.add(binding)
    }

    override suspend fun insertOrUpdateBindingsInternal(bindingsList: List<HostBindingEntity>) {
        for (b in bindingsList) {
            val list = bindings.computeIfAbsent(b.sessionId) { mutableListOf() }
            list.removeAll { it.hostId == b.hostId }
            list.add(b)
        }
    }

    override suspend fun deleteBinding(sessionId: String, hostId: String) {
        bindings[sessionId]?.removeAll { it.hostId == hostId }
        updateFlow()
    }

    override suspend fun deleteBindingsForSession(sessionId: String) {
        bindings.remove(sessionId)
        updateFlow()
    }

    override suspend fun insertOrUpdateMessageInternal(message: UnifiedMessageEntity) {
        val list = messages.computeIfAbsent(message.sessionId) { mutableListOf() }
        val idx = list.indexOfFirst { it.id == message.id }
        if (idx >= 0) {
            list[idx] = message
        } else {
            list.add(message)
        }
    }

    override suspend fun insertMessagesInternal(msgList: List<UnifiedMessageEntity>) {
        for (m in msgList) {
            val list = messages.computeIfAbsent(m.sessionId) { mutableListOf() }
            val idx = list.indexOfFirst { it.id == m.id }
            if (idx >= 0) {
                list[idx] = m
            } else {
                list.add(m)
            }
        }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.remove(sessionId)
        updateFlow()
    }

    override suspend fun getSessionIdForMessage(messageId: String): String? {
        for ((sessionId, list) in messages) {
            if (list.any { it.id == messageId }) return sessionId
        }
        return null
    }

    override suspend fun updateMessageContentInternal(
        messageId: String,
        content: String,
        isStreaming: Boolean,
        thinking: String?,
        toolsJson: String?
    ) {
        for ((_, list) in messages) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val cur = list[idx]
                list[idx] = cur.copy(
                    content = content,
                    isStreaming = isStreaming,
                    thinking = thinking,
                    toolsJson = toolsJson
                )
                break
            }
        }
    }

    override suspend fun updateActiveHost(sessionId: String, hostId: String, updatedAt: Long) {
        val cur = sessions[sessionId]
        if (cur != null) {
            sessions[sessionId] = cur.copy(activeHostId = hostId, updatedAt = updatedAt)
            updateFlow()
        }
    }

    override suspend fun updateBindingSync(
        sessionId: String,
        hostId: String,
        syncedThroughMessageId: String?,
        syncedAt: Long,
        state: String
    ) {
        val list = bindings[sessionId] ?: return
        val idx = list.indexOfFirst { it.hostId == hostId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                syncedThroughMessageId = syncedThroughMessageId,
                syncedAt = syncedAt,
                state = state
            )
            updateFlow()
        }
    }

    override suspend fun updateBindingState(sessionId: String, hostId: String, state: String) {
        val list = bindings[sessionId] ?: return
        val idx = list.indexOfFirst { it.hostId == hostId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(state = state)
            updateFlow()
        }
    }
}
