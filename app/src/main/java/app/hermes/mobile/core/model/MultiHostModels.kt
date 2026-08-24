package app.hermes.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class HermesHostId(val value: String)

@Serializable
data class UnifiedSessionId(val value: String)

enum class HostStatus {
    ONLINE,
    OFFLINE,
    CONNECTING,
    AUTH_REQUIRED,
    AUTH_EXPIRED,
    ERROR;

    companion object {
        fun fromStringOrOffline(raw: String?): HostStatus {
            if (raw.isNullOrBlank()) return OFFLINE
            return try {
                valueOf(raw.trim())
            } catch (_: IllegalArgumentException) {
                OFFLINE
            }
        }
    }
}

@Serializable
data class HermesHost(
    val id: HermesHostId,
    val displayName: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false,
    val enabled: Boolean = true,
    val lastSeenAt: Long = 0L,
    val lastKnownStatus: HostStatus = HostStatus.OFFLINE,
    val certificateFingerprint: String? = null
)

enum class BindingState {
    NOT_CREATED,
    READY,
    CONNECTING,
    RUNNING,
    OFFLINE,
    ERROR
}

@Serializable
data class HostSessionBinding(
    val hostId: HermesHostId,
    val durableSessionId: DurableSessionId,
    val runtimeSessionId: RuntimeSessionId,
    val lastAttachedAt: Long = System.currentTimeMillis(),
    val state: BindingState = BindingState.NOT_CREATED,
    val syncedThroughMessageId: String? = null,
    val syncedAt: Long? = null
)

enum class UnifiedMessageSource {
    USER,
    HERMES,
    SYSTEM,
    TRANSFER,
    A2A
}

@Serializable
data class UnifiedMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val hostId: HermesHostId? = null,
    val source: UnifiedMessageSource = UnifiedMessageSource.HERMES,
    val createdAt: Long = System.currentTimeMillis(),
    val nativeMessageId: String? = null,
    val thinking: String? = null,
    val tools: List<ToolActivity> = emptyList(),
    val isStreaming: Boolean = false
)

@Serializable
data class UnifiedSession(
    val id: UnifiedSessionId,
    val title: String,
    val activeHostId: HermesHostId,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val bindings: Map<HermesHostId, HostSessionBinding> = emptyMap(),
    val timeline: List<UnifiedMessage> = emptyList(),
    val messageCount: Int = timeline.size,
    val lastMessagePreview: String? = null
)

@Serializable
data class A2AContextBinding(
    val sourceHostId: HermesHostId,
    val targetHostId: HermesHostId,
    val contextId: String
)

data class HostGatewayEvent(
    val hostId: HermesHostId,
    val event: GatewayEvent
)

@Serializable
data class HostAttributedApproval(
    val hostId: HermesHostId,
    val hostDisplayName: String,
    val runtimeSessionId: RuntimeSessionId,
    val approval: HermesApproval
)

@Serializable
data class HostAttributedClarify(
    val hostId: HermesHostId,
    val hostDisplayName: String,
    val runtimeSessionId: RuntimeSessionId? = null,
    val request: HermesClarifyRequest
)
