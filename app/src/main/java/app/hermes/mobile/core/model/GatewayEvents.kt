package app.hermes.mobile.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed class GatewayEvent {
    abstract val rawPayload: JsonObject
    open val sessionId: String? get() = null

    data class GatewayReadyEvent(
        val version: String,
        val sessionCount: Int = 0,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class MessageStartEvent(
        val messageId: String,
        val role: String = "assistant",
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class MessageDeltaEvent(
        val messageId: String,
        val delta: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class MessageInterimEvent(
        val messageId: String,
        val content: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class MessageCompleteEvent(
        val messageId: String,
        val content: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ThinkingDeltaEvent(
        val messageId: String,
        val delta: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ReasoningDeltaEvent(
        val messageId: String,
        val delta: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ReasoningAvailableEvent(
        val messageId: String,
        val reasoning: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ToolStartEvent(
        val toolId: String,
        val name: String,
        val input: JsonElement? = null,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ToolProgressEvent(
        val toolId: String,
        val progress: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ToolGeneratingEvent(
        val toolId: String,
        val name: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ToolCompleteEvent(
        val toolId: String,
        val result: String,
        val isError: Boolean = false,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ApprovalRequestEvent(
        val requestId: String,
        val command: String? = null,
        val description: String? = null,
        val choices: List<String> = listOf("once", "deny"),
        val sessionKey: String? = null,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ClarifyRequestEvent(
        val requestId: String,
        val questionId: String? = null,
        val question: String,
        val promptType: ClarifyType = ClarifyType.CLARIFY,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class SudoRequestEvent(
        val requestId: String,
        val question: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class SecretRequestEvent(
        val requestId: String,
        val question: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class StatusUpdateEvent(
        val status: String,
        val message: String? = null,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class SessionUsageEvent(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val totalTokens: Long = 0,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class SessionInfoEvent(
        val info: SessionInfo,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class BackgroundCompleteEvent(
        val taskId: String,
        val result: String? = null,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class ErrorEvent(
        val code: Int = -1,
        val message: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    data class UnknownGatewayEvent(
        val eventType: String,
        override val sessionId: String? = null,
        override val rawPayload: JsonObject
    ) : GatewayEvent()

    companion object {
        fun parse(root: JsonObject): GatewayEvent? {
            val params = (root["params"] as? JsonObject) ?: root

            // 1. event type -> params["type"]
            val eventType = params["type"]?.asStringOrNull()
                ?: params["event"]?.asStringOrNull()
                ?: root["type"]?.asStringOrNull()
                ?: root["event"]?.asStringOrNull()
                ?: ""

            // 2. runtime session -> params["session_id"] (Do NOT search inside payload)
            val sessionId = params["session_id"]?.asStringOrNull()
                ?: params["session_key"]?.asStringOrNull()
                ?: root["session_id"]?.asStringOrNull()
                ?: root["session_key"]?.asStringOrNull()

            // 3. event body -> params["payload"]
            val payloadObj = (params["payload"] as? JsonObject)
                ?: (params["data"] as? JsonObject)
                ?: (root["payload"] as? JsonObject)
                ?: (root["data"] as? JsonObject)
                ?: params

            fun getString(vararg keys: String): String {
                for (k in keys) {
                    val v = payloadObj[k]?.asStringOrNull()
                    if (!v.isNullOrEmpty()) return v
                }
                return ""
            }

            fun getNullableString(vararg keys: String): String? {
                for (k in keys) {
                    val v = payloadObj[k]?.asStringOrNull()
                    if (v != null) return v
                }
                return null
            }

            fun getLong(vararg keys: String): Long {
                for (k in keys) {
                    val v = payloadObj[k]?.asLongOrNull()
                    if (v != null) return v
                }
                return 0L
            }

            fun getInt(vararg keys: String): Int {
                for (k in keys) {
                    val v = payloadObj[k]?.asIntOrNull()
                    if (v != null) return v
                }
                return 0
            }

            fun getBoolean(vararg keys: String): Boolean {
                for (k in keys) {
                    val v = payloadObj[k]?.asBooleanOrNull()
                    if (v != null) return v
                }
                return false
            }

            fun getStringList(key: String): List<String> {
                val array = payloadObj[key] as? JsonArray ?: return emptyList()
                return array.mapNotNull { it.asStringOrNull() }
            }

            return when (eventType) {
                "gateway.ready" -> GatewayReadyEvent(
                    version = getString("version", "server_version"),
                    sessionCount = getInt("session_count", "sessions"),
                    rawPayload = root
                )
                "message.start" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    MessageStartEvent(
                        messageId = messageId,
                        role = getString("role").ifEmpty { "assistant" },
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "message.delta" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    MessageDeltaEvent(
                        messageId = messageId,
                        delta = getString("delta", "text", "chunk"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "message.interim" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    MessageInterimEvent(
                        messageId = messageId,
                        content = getString("content", "text"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "message.complete" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    MessageCompleteEvent(
                        messageId = messageId,
                        content = getString("content", "text"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "thinking.delta" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    ThinkingDeltaEvent(
                        messageId = messageId,
                        delta = getString("delta", "text", "chunk"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "reasoning.delta" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    ReasoningDeltaEvent(
                        messageId = messageId,
                        delta = getString("delta", "text", "chunk"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "reasoning.available" -> {
                    val messageId = getString("message_id", "id")
                    if (messageId.isBlank()) return null
                    ReasoningAvailableEvent(
                        messageId = messageId,
                        reasoning = getString("reasoning", "content"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "tool.start" -> {
                    val toolId = getString("tool_id", "id")
                    if (toolId.isBlank()) return null
                    ToolStartEvent(
                        toolId = toolId,
                        name = getString("name", "tool_name"),
                        input = payloadObj["input"],
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "tool.progress" -> {
                    val toolId = getString("tool_id", "id")
                    if (toolId.isBlank()) return null
                    ToolProgressEvent(
                        toolId = toolId,
                        progress = getString("progress", "message"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "tool.generating" -> {
                    val toolId = getString("tool_id", "id")
                    if (toolId.isBlank()) return null
                    ToolGeneratingEvent(
                        toolId = toolId,
                        name = getString("name", "tool_name"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "tool.complete" -> {
                    val toolId = getString("tool_id", "id")
                    if (toolId.isBlank()) return null
                    ToolCompleteEvent(
                        toolId = toolId,
                        result = getString("result", "output"),
                        isError = getBoolean("is_error", "error"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "approval.request" -> {
                    val requestId = getString("request_id", "id")
                    if (requestId.isBlank()) return null
                    val choices = getStringList("choices")
                    ApprovalRequestEvent(
                        requestId = requestId,
                        command = getNullableString("command"),
                        description = getNullableString("description", "prompt"),
                        choices = if (choices.isNotEmpty()) choices else listOf("once", "deny"),
                        sessionKey = sessionId,
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "clarify.request" -> {
                    val requestId = getString("request_id", "id")
                    if (requestId.isBlank()) return null
                    ClarifyRequestEvent(
                        requestId = requestId,
                        questionId = getNullableString("question_id", "questionId"),
                        question = getString("question", "prompt"),
                        promptType = ClarifyType.CLARIFY,
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "sudo.request" -> {
                    val requestId = getString("request_id", "id")
                    if (requestId.isBlank()) return null
                    SudoRequestEvent(
                        requestId = requestId,
                        question = getString("question", "prompt").ifEmpty { "Administrator password required:" },
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "secret.request" -> {
                    val requestId = getString("request_id", "id")
                    if (requestId.isBlank()) return null
                    SecretRequestEvent(
                        requestId = requestId,
                        question = getString("question", "prompt").ifEmpty { "Secret / Token required:" },
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "status.update" -> StatusUpdateEvent(
                    status = getString("status"),
                    message = getNullableString("message"),
                    sessionId = sessionId,
                    rawPayload = root
                )
                "session.usage" -> SessionUsageEvent(
                    inputTokens = getLong("input_tokens", "prompt_tokens"),
                    outputTokens = getLong("output_tokens", "completion_tokens"),
                    totalTokens = getLong("total_tokens"),
                    sessionId = sessionId,
                    rawPayload = root
                )
                "session.info" -> SessionInfoEvent(
                    info = SessionInfo(
                        model = getNullableString("model"),
                        provider = getNullableString("provider"),
                        cwd = getNullableString("cwd"),
                        branch = getNullableString("branch"),
                        project = getNullableString("project")
                    ),
                    sessionId = sessionId,
                    rawPayload = root
                )
                "background.complete" -> {
                    val taskId = getString("task_id", "id")
                    if (taskId.isBlank()) return null
                    BackgroundCompleteEvent(
                        taskId = taskId,
                        result = getNullableString("result"),
                        sessionId = sessionId,
                        rawPayload = root
                    )
                }
                "error" -> ErrorEvent(
                    code = getInt("code"),
                    message = getString("message").ifEmpty { "Unknown error" },
                    sessionId = sessionId,
                    rawPayload = root
                )
                else -> UnknownGatewayEvent(
                    eventType = eventType.ifEmpty { "unknown" },
                    sessionId = sessionId,
                    rawPayload = root
                )
            }
        }
    }
}

fun JsonElement?.asStringOrNull(): String? {
    if (this == null || this is kotlinx.serialization.json.JsonNull) return null
    val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return primitive.content
}

fun JsonElement?.asIntOrNull(): Int? {
    if (this == null || this is kotlinx.serialization.json.JsonNull) return null
    val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return primitive.intOrNull
}

fun JsonElement?.asLongOrNull(): Long? {
    if (this == null || this is kotlinx.serialization.json.JsonNull) return null
    val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return primitive.longOrNull
}

fun JsonElement?.asBooleanOrNull(): Boolean? {
    if (this == null || this is kotlinx.serialization.json.JsonNull) return null
    val primitive = this as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return primitive.booleanOrNull
}


