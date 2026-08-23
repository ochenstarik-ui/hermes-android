package app.hermes.mobile.core.storage

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val enabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val lastKnownStatus: String = "OFFLINE"
)

@Entity(tableName = "unified_sessions")
data class UnifiedSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val activeHostId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "host_bindings",
    primaryKeys = ["sessionId", "hostId"],
    foreignKeys = [
        ForeignKey(
            entity = UnifiedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("hostId")]
)
data class HostBindingEntity(
    val sessionId: String,
    val hostId: String,
    val durableSessionId: String,
    val runtimeSessionId: String,
    val lastAttachedAt: Long = System.currentTimeMillis(),
    val state: String = "NOT_CREATED",
    val syncedThroughMessageId: String? = null,
    val syncedAt: Long? = null
)

@Entity(
    tableName = "unified_messages",
    foreignKeys = [
        ForeignKey(
            entity = UnifiedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("createdAt")]
)
data class UnifiedMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val hostId: String? = null,
    val source: String = "HERMES",
    val createdAt: Long = System.currentTimeMillis(),
    val nativeMessageId: String? = null,
    val thinking: String? = null,
    val toolsJson: String? = null,
    val isStreaming: Boolean = false
)

data class UnifiedSessionWithDetails(
    @Embedded val session: UnifiedSessionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val bindings: List<HostBindingEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val messages: List<UnifiedMessageEntity>
)
