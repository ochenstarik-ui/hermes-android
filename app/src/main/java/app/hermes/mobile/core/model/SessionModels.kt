package app.hermes.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DurableSessionId(val value: String)

@Serializable
@JvmInline
value class RuntimeSessionId(val value: String)

@Serializable
data class SessionSummary(
    val id: DurableSessionId,
    val title: String = "",
    val preview: String = "",
    val startedAt: Long = 0L,
    val messageCount: Int = 0,
    val source: String = "android"
)

@Serializable
data class SessionInfo(
    val model: String? = null,
    val provider: String? = null,
    val cwd: String? = null,
    val branch: String? = null,
    val project: String? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class ToolActivity(
    val id: String,
    val name: String,
    val status: String,
    val progress: String? = null,
    val result: String? = null,
    val isError: Boolean = false
)

@Serializable
data class HermesApproval(
    val requestId: String,
    val command: String? = null,
    val description: String? = null,
    val choices: List<String> = listOf("once", "deny")
)

enum class ClarifyType {
    CLARIFY,
    SUDO,
    SECRET
}

@Serializable
data class HermesClarifyRequest(
    val requestId: String,
    val questionId: String? = null,
    val question: String,
    val promptType: ClarifyType = ClarifyType.CLARIFY
)

data class CreateSessionResult(
    val durableId: DurableSessionId,
    val runtimeId: RuntimeSessionId
)

data class ResumeSessionResult(
    val durableId: DurableSessionId,
    val runtimeId: RuntimeSessionId
)

data class PromptSubmitResult(
    val turnId: String? = null,
    val accepted: Boolean = true
)
