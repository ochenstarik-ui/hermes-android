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

    @Transaction
    @Query("SELECT * FROM unified_sessions WHERE id = :sessionId LIMIT 1")
    fun getSessionWithDetailsFlow(sessionId: String): Flow<UnifiedSessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM unified_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionWithDetails(sessionId: String): UnifiedSessionWithDetails?

    @Query("SELECT * FROM unified_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessagesForSession(sessionId: String): List<UnifiedMessageEntity>

    @Query("SELECT * FROM host_bindings WHERE sessionId = :sessionId")
    suspend fun getBindingsForSession(sessionId: String): List<HostBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: UnifiedSessionEntity)

    @Update
    suspend fun updateSession(session: UnifiedSessionEntity)

    @Query("DELETE FROM unified_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBinding(binding: HostBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateBindings(bindings: List<HostBindingEntity>)

    @Query("DELETE FROM host_bindings WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun deleteBinding(sessionId: String, hostId: String)

    @Query("DELETE FROM host_bindings WHERE sessionId = :sessionId")
    suspend fun deleteBindingsForSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessage(message: UnifiedMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<UnifiedMessageEntity>)

    @Query("DELETE FROM unified_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("UPDATE unified_messages SET content = :content, isStreaming = :isStreaming, thinking = :thinking, toolsJson = :toolsJson WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: String, content: String, isStreaming: Boolean, thinking: String?, toolsJson: String?)

    @Query("UPDATE unified_sessions SET activeHostId = :hostId, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateActiveHost(sessionId: String, hostId: String, updatedAt: Long)

    @Query("UPDATE host_bindings SET syncedThroughMessageId = :syncedThroughMessageId, syncedAt = :syncedAt, state = :state WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun updateBindingSync(sessionId: String, hostId: String, syncedThroughMessageId: String?, syncedAt: Long, state: String)

    @Query("UPDATE host_bindings SET state = :state WHERE sessionId = :sessionId AND hostId = :hostId")
    suspend fun updateBindingState(sessionId: String, hostId: String, state: String)
}
