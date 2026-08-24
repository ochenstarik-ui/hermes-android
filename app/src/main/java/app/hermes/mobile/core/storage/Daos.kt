package app.hermes.mobile.core.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY displayName ASC")
    fun getHostsFlow(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts ORDER BY displayName ASC")
    suspend fun getHosts(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :hostId LIMIT 1")
    suspend fun getHost(hostId: String): HostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateHost(host: HostEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHosts(hosts: List<HostEntity>)

    @Query("DELETE FROM hosts WHERE id = :hostId")
    suspend fun deleteHost(hostId: String)

    @Query("UPDATE hosts SET lastKnownStatus = :status, lastSeenAt = :lastSeenAt WHERE id = :hostId")
    suspend fun updateHostStatus(hostId: String, status: String, lastSeenAt: Long)
}

@Dao
interface UnifiedSessionDao {
    @Query("SELECT * FROM unified_sessions ORDER BY updatedAt DESC")
    fun getSessionsFlow(): Flow<List<UnifiedSessionEntity>>

    @Query("SELECT * FROM unified_sessions ORDER BY updatedAt DESC")
    suspend fun getSessions(): List<UnifiedSessionEntity>

    @Query("SELECT * FROM unified_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): UnifiedSessionEntity?

    @Query("SELECT * FROM unified_sessions WHERE id = :sessionId LIMIT 1")
    fun getSessionFlow(sessionId: String): Flow<UnifiedSessionEntity?>

    @Query("SELECT * FROM host_bindings WHERE sessionId = :sessionId")
    suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity>

    @Query("SELECT * FROM host_bindings WHERE sessionId = :sessionId")
    fun getBindingsForSessionFlow(sessionId: String): Flow<List<HostBindingEntity>>

    @Query("SELECT * FROM unified_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity>

    @Query("SELECT * FROM unified_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun getMessagesForSessionFlow(sessionId: String): Flow<List<UnifiedMessageEntity>>

    @Transaction
    suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails? {
        val s = getSession(sessionId) ?: return null
        val b = getBindingsForSession(sessionId)
        val m = getMessagesForSession(sessionId)
        return UnifiedSessionWithDetails(session = s, bindings = b, messages = m)
    }

    fun getSessionWithDetailsFlow(sessionId: String): Flow<UnifiedSessionWithDetails?> {
        return kotlinx.coroutines.flow.combine(
            getSessionFlow(sessionId),
            getBindingsForSessionFlow(sessionId),
            getMessagesForSessionFlow(sessionId)
        ) { session, bindings, messages ->
            if (session == null) null
            else UnifiedSessionWithDetails(session = session, bindings = bindings, messages = messages)
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: UnifiedSessionEntity)

    @Update
    suspend fun updateSession(session: UnifiedSessionEntity)

    @Query("DELETE FROM unified_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("UPDATE unified_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionUpdatedAt(sessionId: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBindingInternal(binding: HostBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBindingsInternal(bindings: List<HostBindingEntity>)

    @Transaction
    suspend fun insertOrUpdateBinding(binding: HostBindingEntity) {
        insertOrUpdateBindingInternal(binding)
        updateSessionUpdatedAt(binding.sessionId, System.currentTimeMillis())
    }

    @Transaction
    suspend fun insertOrUpdateBindings(bindings: List<HostBindingEntity>) {
        if (bindings.isEmpty()) return
        insertOrUpdateBindingsInternal(bindings)
        val now = System.currentTimeMillis()
        val sessionIds = bindings.map { it.sessionId }.distinct()
        for (sid in sessionIds) {
            updateSessionUpdatedAt(sid, now)
        }
    }

    @Query("DELETE FROM host_bindings WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun deleteBinding(sessionId: String, hostId: String)

    @Query("DELETE FROM host_bindings WHERE sessionId = :sessionId")
    suspend fun deleteBindingsForSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessageInternal(message: UnifiedMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessagesInternal(messages: List<UnifiedMessageEntity>)

    @Transaction
    suspend fun insertOrUpdateMessage(message: UnifiedMessageEntity) {
        insertOrUpdateMessageInternal(message)
        updateSessionUpdatedAt(message.sessionId, System.currentTimeMillis())
    }

    @Transaction
    suspend fun insertMessages(messages: List<UnifiedMessageEntity>) {
        if (messages.isEmpty()) return
        insertMessagesInternal(messages)
        val now = System.currentTimeMillis()
        val sessionIds = messages.map { it.sessionId }.distinct()
        for (sid in sessionIds) {
            updateSessionUpdatedAt(sid, now)
        }
    }

    @Query("DELETE FROM unified_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("SELECT sessionId FROM unified_messages WHERE id = :messageId LIMIT 1")
    suspend fun getSessionIdForMessage(messageId: String): String?

    @Query("UPDATE unified_messages SET content = :content, isStreaming = :isStreaming, thinking = :thinking, toolsJson = :toolsJson WHERE id = :messageId")
    suspend fun updateMessageContentInternal(messageId: String, content: String, isStreaming: Boolean, thinking: String?, toolsJson: String?)

    @Transaction
    suspend fun updateMessageContent(messageId: String, content: String, isStreaming: Boolean, thinking: String?, toolsJson: String?) {
        updateMessageContentInternal(messageId, content, isStreaming, thinking, toolsJson)
        val sid = getSessionIdForMessage(messageId)
        if (sid != null) {
            updateSessionUpdatedAt(sid, System.currentTimeMillis())
        }
    }

    @Query("UPDATE unified_sessions SET activeHostId = :hostId, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveHost(sessionId: String, hostId: String, updatedAt: Long)

    @Query("UPDATE host_bindings SET syncedThroughMessageId = :syncedThroughMessageId, syncedAt = :syncedAt, state = :state WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun updateBindingSync(sessionId: String, hostId: String, syncedThroughMessageId: String?, syncedAt: Long, state: String)

    @Query("UPDATE host_bindings SET state = :state WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun updateBindingState(sessionId: String, hostId: String, state: String)
}

@Dao
interface UsedNonceDao {
    @Query("SELECT EXISTS(SELECT 1 FROM used_nonces WHERE nonce = :nonce LIMIT 1)")
    suspend fun isNonceUsed(nonce: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNonce(entity: UsedNonceEntity)

    @Query("DELETE FROM used_nonces WHERE expiresAt < :now")
    suspend fun purgeExpiredNonces(now: Long)

    @Query("SELECT * FROM used_nonces")
    suspend fun getAllNonces(): List<UsedNonceEntity>
}
