package app.hermes.mobile.core.storage

import kotlinx.coroutines.flow.Flow
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
    private val sessionsFlow = MutableStateFlow<List<UnifiedSessionEntity>>(emptyList())

    private fun updateFlow() {
        sessionsFlow.value = sessions.values.sortedByDescending { it.updatedAt }
    }

    override fun getSessionsFlow(): Flow<List<UnifiedSessionEntity>> = sessionsFlow

    override suspend fun getSessions(): List<UnifiedSessionEntity> = sessions.values.sortedByDescending { it.updatedAt }

    override fun getSessionWithDetailsFlow(sessionId: String): Flow<UnifiedSessionWithDetails?> {
        return sessionsFlow.map { getSessionWithDetails(sessionId) }
    }

    override suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails? {
        val s = sessions[sessionId] ?: return null
        val b = bindings[sessionId] ?: emptyList()
        val m = messages[sessionId] ?: emptyList()
        return UnifiedSessionWithDetails(session = s, bindings = b, messages = m)
    }

    override suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity> {
        return messages[sessionId]?.sortedBy { it.createdAt } ?: emptyList()
    }

    override suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity> {
        return bindings[sessionId] ?: emptyList()
    }

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

    override suspend fun insertOrUpdateBinding(binding: HostBindingEntity) {
        val list = bindings.computeIfAbsent(binding.sessionId) { mutableListOf() }
        list.removeAll { it.hostId == binding.hostId }
        list.add(binding)
    }

    override suspend fun insertOrUpdateBindings(bindingList: List<HostBindingEntity>) {
        for (b in bindingList) insertOrUpdateBinding(b)
    }

    override suspend fun deleteBinding(sessionId: String, hostId: String) {
        bindings[sessionId]?.removeAll { it.hostId == hostId }
    }

    override suspend fun deleteBindingsForSession(sessionId: String) {
        bindings.remove(sessionId)
    }

    override suspend fun insertOrUpdateMessage(message: UnifiedMessageEntity) {
        val list = messages.computeIfAbsent(message.sessionId) { mutableListOf() }
        val idx = list.indexOfFirst { it.id == message.id }
        if (idx >= 0) {
            list[idx] = message
        } else {
            list.add(message)
        }
    }

    override suspend fun insertMessages(msgList: List<UnifiedMessageEntity>) {
        for (m in msgList) insertOrUpdateMessage(m)
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.remove(sessionId)
    }

    override suspend fun updateMessageContent(
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
        }
    }

    override suspend fun updateBindingState(sessionId: String, hostId: String, state: String) {
        val list = bindings[sessionId] ?: return
        val idx = list.indexOfFirst { it.hostId == hostId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(state = state)
        }
    }
}
