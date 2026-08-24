package app.hermes.mobile.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

class FakeHostDao : HostDao {
    private val lock = Any()
    private val storage = ConcurrentHashMap<String, HostEntity>()
    private val flow = MutableStateFlow<List<HostEntity>>(emptyList())

    private fun updateFlow() {
        val snapshot = synchronized(lock) {
            storage.values.sortedBy { it.displayName }.map { it.copy() }
        }
        flow.value = snapshot
    }

    override fun getHostsFlow(): Flow<List<HostEntity>> = flow

    override suspend fun getHosts(): List<HostEntity> = synchronized(lock) {
        storage.values.sortedBy { it.displayName }.map { it.copy() }
    }

    override suspend fun getHost(hostId: String): HostEntity? = synchronized(lock) {
        storage[hostId]?.copy()
    }

    override suspend fun insertOrUpdateHost(host: HostEntity) {
        synchronized(lock) {
            storage[host.id] = host.copy()
            updateFlow()
        }
    }

    override suspend fun insertHosts(hosts: List<HostEntity>) {
        synchronized(lock) {
            for (h in hosts) {
                storage[h.id] = h.copy()
            }
            updateFlow()
        }
    }

    override suspend fun deleteHost(hostId: String) {
        synchronized(lock) {
            storage.remove(hostId)
            updateFlow()
        }
    }

    override suspend fun updateHostStatus(hostId: String, status: String, lastSeenAt: Long) {
        synchronized(lock) {
            val existing = storage[hostId]
            if (existing != null) {
                storage[hostId] = existing.copy(lastKnownStatus = status, lastSeenAt = lastSeenAt)
                updateFlow()
            }
        }
    }
}

class FakeUnifiedSessionDao : UnifiedSessionDao {
    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, UnifiedSessionEntity>()
    private val bindings = ConcurrentHashMap<String, MutableList<HostBindingEntity>>()
    private val messages = ConcurrentHashMap<String, MutableList<UnifiedMessageEntity>>()
    private val _sessionsFlow = MutableSharedFlow<List<UnifiedSessionEntity>>(replay = 1)

    private val messageComparator = compareBy<UnifiedMessageEntity> { it.createdAt }.thenBy { it.id }

    init {
        _sessionsFlow.tryEmit(emptyList<UnifiedSessionEntity>())
    }

    private fun updateFlow() {
        val list = synchronized(lock) {
            sessions.values.sortedByDescending { it.updatedAt }.map { it.copy() }
        }
        _sessionsFlow.tryEmit(list)
    }

    override fun getSessionsFlow(): Flow<List<UnifiedSessionEntity>> = _sessionsFlow

    override suspend fun getSessions(): List<UnifiedSessionEntity> = synchronized(lock) {
        sessions.values.sortedByDescending { it.updatedAt }.map { it.copy() }
    }

    override suspend fun getSession(sessionId: String): UnifiedSessionEntity? = synchronized(lock) {
        sessions[sessionId]?.copy()
    }

    override fun getSessionFlow(sessionId: String): Flow<UnifiedSessionEntity?> =
        _sessionsFlow.map { synchronized(lock) { sessions[sessionId]?.copy() } }

    override fun getSessionWithDetailsFlow(sessionId: String): Flow<UnifiedSessionWithDetails?> {
        return _sessionsFlow.map { _: List<UnifiedSessionEntity> -> getSessionWithDetails(sessionId) }
    }

    override suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails? = synchronized(lock) {
        val s = sessions[sessionId]?.copy() ?: return@synchronized null
        val b = bindings[sessionId]?.map { it.copy() } ?: emptyList()
        val m = messages[sessionId]?.sortedWith(messageComparator)?.map { it.copy() } ?: emptyList()
        UnifiedSessionWithDetails(session = s, bindings = b, messages = m)
    }

    override suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity> = synchronized(lock) {
        messages[sessionId]?.sortedWith(messageComparator)?.map { it.copy() } ?: emptyList()
    }

    override fun getMessagesForSessionFlow(sessionId: String): Flow<List<UnifiedMessageEntity>> =
        _sessionsFlow.map { getMessagesForSession(sessionId) }

    override suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity> = synchronized(lock) {
        bindings[sessionId]?.map { it.copy() } ?: emptyList()
    }

    override fun getBindingsForSessionFlow(sessionId: String): Flow<List<HostBindingEntity>> =
        _sessionsFlow.map { getBindingsForSession(sessionId) }

    override suspend fun insertSession(session: UnifiedSessionEntity) {
        synchronized(lock) {
            sessions[session.id] = session.copy()
            updateFlow()
        }
    }

    override suspend fun updateSession(session: UnifiedSessionEntity) {
        synchronized(lock) {
            sessions[session.id] = session.copy()
            updateFlow()
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        synchronized(lock) {
            sessions.remove(sessionId)
            bindings.remove(sessionId)
            messages.remove(sessionId)
            updateFlow()
        }
    }

    override suspend fun updateSessionUpdatedAt(sessionId: String, updatedAt: Long) {
        synchronized(lock) {
            val s = sessions[sessionId]
            if (s != null) {
                sessions[sessionId] = s.copy(updatedAt = updatedAt)
                updateFlow()
            }
        }
    }

    override suspend fun insertOrUpdateBindingInternal(binding: HostBindingEntity) {
        synchronized(lock) {
            val list = bindings.computeIfAbsent(binding.sessionId) { mutableListOf() }
            list.removeAll { it.hostId == binding.hostId }
            list.add(binding.copy())
        }
    }

    override suspend fun insertOrUpdateBindingsInternal(bindingsList: List<HostBindingEntity>) {
        synchronized(lock) {
            for (b in bindingsList) {
                val list = bindings.computeIfAbsent(b.sessionId) { mutableListOf() }
                list.removeAll { it.hostId == b.hostId }
                list.add(b.copy())
            }
        }
    }

    override suspend fun deleteBinding(sessionId: String, hostId: String) {
        synchronized(lock) {
            bindings[sessionId]?.removeAll { it.hostId == hostId }
            updateFlow()
        }
    }

    override suspend fun deleteBindingsForSession(sessionId: String) {
        synchronized(lock) {
            bindings.remove(sessionId)
            updateFlow()
        }
    }

    override suspend fun insertOrUpdateMessageInternal(message: UnifiedMessageEntity) {
        synchronized(lock) {
            val list = messages.computeIfAbsent(message.sessionId) { mutableListOf() }
            val idx = list.indexOfFirst { it.id == message.id }
            if (idx >= 0) {
                list[idx] = message.copy()
            } else {
                list.add(message.copy())
            }
        }
    }

    override suspend fun insertMessagesInternal(msgList: List<UnifiedMessageEntity>) {
        synchronized(lock) {
            for (m in msgList) {
                val list = messages.computeIfAbsent(m.sessionId) { mutableListOf() }
                val idx = list.indexOfFirst { it.id == m.id }
                if (idx >= 0) {
                    list[idx] = m.copy()
                } else {
                    list.add(m.copy())
                }
            }
        }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        synchronized(lock) {
            messages.remove(sessionId)
            updateFlow()
        }
    }

    override suspend fun getSessionIdForMessage(messageId: String): String? = synchronized(lock) {
        for ((sessionId, list) in messages) {
            if (list.any { it.id == messageId }) return@synchronized sessionId
        }
        null
    }

    override suspend fun updateMessageContentInternal(
        messageId: String,
        content: String,
        isStreaming: Boolean,
        thinking: String?,
        toolsJson: String?
    ) {
        synchronized(lock) {
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
    }

    override suspend fun updateActiveHost(sessionId: String, hostId: String, updatedAt: Long) {
        synchronized(lock) {
            val cur = sessions[sessionId]
            if (cur != null) {
                sessions[sessionId] = cur.copy(activeHostId = hostId, updatedAt = updatedAt)
                updateFlow()
            }
        }
    }

    override suspend fun updateBindingSync(
        sessionId: String,
        hostId: String,
        syncedThroughMessageId: String?,
        syncedAt: Long,
        state: String
    ) {
        synchronized(lock) {
            val list = bindings[sessionId] ?: return@synchronized
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
    }

    override suspend fun updateBindingState(sessionId: String, hostId: String, state: String) {
        synchronized(lock) {
            val list = bindings[sessionId] ?: return@synchronized
            val idx = list.indexOfFirst { it.hostId == hostId }
            if (idx >= 0) {
                list[idx] = list[idx].copy(state = state)
                updateFlow()
            }
        }
    }
}
