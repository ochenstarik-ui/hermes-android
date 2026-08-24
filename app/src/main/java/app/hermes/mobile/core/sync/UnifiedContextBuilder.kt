package app.hermes.mobile.core.sync

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.MessageRole
import app.hermes.mobile.core.model.UnifiedMessage
import app.hermes.mobile.core.model.UnifiedSession
import kotlin.math.max

data class SyncContextResult(
    val contextPrompt: String,
    val latestSyncedMessageId: String?,
    val hasNewContext: Boolean
)

object UnifiedContextBuilder {

    private val bearerTokenRegex = Regex("Bearer\\s+[a-zA-Z0-9_\\-\\.]+", RegexOption.IGNORE_CASE)
    private val genericSecretRegex = Regex("(?i)(password|secret|api[_-]?key|token|auth_token)\\s*[:=]\\s*[\"']?([^\\s,\"';]+)[\"']?")
    private val openAiKeyRegex = Regex("sk-[a-zA-Z0-9]{20,}")
    private val githubTokenRegex = Regex("gh[pousr]_[a-zA-Z0-9]{20,}")
    private val jwtTokenRegex = Regex("ey[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_=]{10,}\\.[A-Za-z0-9-_.+/=]{10,}")

    fun sanitizeContent(content: String): String {
        var sanitized = content
        sanitized = bearerTokenRegex.replace(sanitized, "Bearer [REDACTED_TOKEN]")
        sanitized = openAiKeyRegex.replace(sanitized, "[REDACTED_API_KEY]")
        sanitized = githubTokenRegex.replace(sanitized, "[REDACTED_TOKEN]")
        sanitized = jwtTokenRegex.replace(sanitized, "[REDACTED_JWT]")
        sanitized = genericSecretRegex.replace(sanitized) { matchResult ->
            "${matchResult.groupValues[1]}: [REDACTED_SECRET]"
        }
        return sanitized
    }

    fun buildContextSyncPayload(
        session: UnifiedSession,
        targetHost: HermesHost,
        allHosts: Map<HermesHostId, HermesHost> = emptyMap(),
        syncedThroughMessageId: String? = null
    ): SyncContextResult {
        val timeline = session.timeline
        if (timeline.isEmpty()) {
            return SyncContextResult(contextPrompt = "", latestSyncedMessageId = null, hasNewContext = false)
        }

        val maxMessages = 10
        var startIndex = 0

        if (syncedThroughMessageId != null) {
            val idx = timeline.indexOfFirst { it.id == syncedThroughMessageId }
            startIndex = if (idx >= 0) idx + 1 else max(0, timeline.size - maxMessages)
        } else {
            startIndex = max(0, timeline.size - maxMessages)
        }

        if (timeline.size - startIndex > maxMessages) {
            startIndex = max(0, timeline.size - maxMessages)
        }

        val messagesToSync = timeline.subList(startIndex, timeline.size)
        if (messagesToSync.isEmpty()) {
            return SyncContextResult(
                contextPrompt = "",
                latestSyncedMessageId = syncedThroughMessageId,
                hasNewContext = false
            )
        }

        val latestMessageId = messagesToSync.last().id

        val sb = StringBuilder()
        sb.appendLine("[Unified Hermes Session Context Transfer]")
        sb.appendLine("You are continuing a unified conversation that previously ran across Hermes host instances.")
        sb.appendLine("Target Host: ${targetHost.displayName}")
        sb.appendLine("Session Title: ${session.title}")
        sb.appendLine("--- Prior Conversation Turns ---")

        for (msg in messagesToSync) {
            val sanitized = sanitizeContent(msg.content)
            when (msg.role) {
                MessageRole.USER -> {
                    sb.appendLine("User: $sanitized")
                }
                MessageRole.ASSISTANT -> {
                    val hostLabel = if (msg.hostId != null) {
                        allHosts[msg.hostId]?.displayName ?: msg.hostId.value.take(8)
                    } else {
                        "Hermes"
                    }
                    sb.appendLine("[$hostLabel]: $sanitized")
                    if (msg.tools.isNotEmpty()) {
                        val toolNames = msg.tools.joinToString(", ") { "${it.name} (${it.status})" }
                        sb.appendLine("[$hostLabel Tool Activities: $toolNames]")
                    }
                }
                MessageRole.SYSTEM -> {
                    sb.appendLine("System: $sanitized")
                }
            }
        }

        sb.appendLine("--- End Prior Conversation ---")
        sb.appendLine("Please continue assisting the user seamlessly using the above context.")

        return SyncContextResult(
            contextPrompt = sb.toString().trim(),
            latestSyncedMessageId = latestMessageId,
            hasNewContext = true
        )
    }
}
