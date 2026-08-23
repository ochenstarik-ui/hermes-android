package app.hermes.mobile.core.model

import kotlinx.serialization.json.Json
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
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(root: JsonObject): GatewayEvent {
            // Find event name and data container
            var eventType = ""
            var dataObj: JsonObject = root

            if (root.containsKey("method")) {
                val method = root["method"]?.jsonPrimitive?.content ?: ""
                if (method == "event" && root.containsKey("params")) {
                    val params = root["params"]?.jsonObject ?: JsonObject(emptyMap())
                    eventType = params["event"]?.jsonPrimitive?.content
                        ?: params["type"]?.jsonPrimitive?.content
                        ?: ""
                    dataObj = params["data"]?.jsonObject
                        ?: params["payload"]?.jsonObject
                        ?: params
                } else if (method.isNotEmpty()) {
                    eventType = method
                    dataObj = root["params"]?.jsonObject ?: root
                }
            }

            if (eventType.isEmpty()) {
                eventType = root["event"]?.jsonPrimitive?.content
                    ?: root["type"]?.jsonPrimitive?.content
                    ?: ""
                if (root.containsKey("data") && root["data"] is JsonObject) {
                    dataObj = root["data"]!!.jsonObject
                } else if (root.containsKey("payload") && root["payload"] is JsonObject) {
                    dataObj = root["payload"]!!.jsonObject
                }
            }

            fun getString(vararg keys: String): String {
                for (k in keys) {
                    val v = dataObj[k]?.jsonPrimitive?.content ?: root[k]?.jsonPrimitive?.content
                    if (v != null) return v
                }
                return ""
            }

            fun getNullableString(vararg keys: String): String? {
                for (k in keys) {
                    val el = dataObj[k] ?: root[k]
                    val v = el?.jsonPrimitive?.content
                    if (v != null) return v
                }
                return null
            }

            fun getLong(vararg keys: String): Long {
                for (k in keys) {
                    val v = (dataObj[k]?.jsonPrimitive ?: root[k]?.jsonPrimitive)?.longOrNull
                    if (v != null) return v
                }
                return 0L
            }

            fun getInt(vararg keys: String): Int {
                for (k in keys) {
                    val v = (dataObj[k]?.jsonPrimitive ?: root[k]?.jsonPrimitive)?.intOrNull
                    if (v != null) return v
                }
                return 0
            }

            fun getBoolean(vararg keys: String): Boolean {
                for (k in keys) {
                    val v = (dataObj[k]?.jsonPrimitive ?: root[k]?.jsonPrimitive)?.booleanOrNull
                    if (v != null) return v
                }
                return false
            }

            fun getStringList(key: String): List<String> {
                val array = (dataObj[key] ?: root[key]) as? JsonArray ?: return emptyList()
                return array.mapNotNull { it.jsonPrimitive.content }
            }

            val sessionKey = getNullableString("session_id", "session_key", "sessionKey", "sessionId")

            return when (eventType) {
                "gateway.ready" -> GatewayReadyEvent(
                    version = getString("version", "server_version"),
                    sessionCount = getInt("session_count", "sessions"),
                    rawPayload = root
                )
                "message.start" -> MessageStartEvent(
                    messageId = getString("message_id", "id"),
                    role = getString("role").ifEmpty { "assistant" },
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "message.delta" -> MessageDeltaEvent(
                    messageId = getString("message_id", "id"),
                    delta = getString("delta", "text", "chunk"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "message.interim" -> MessageInterimEvent(
                    messageId = getString("message_id", "id"),
                    content = getString("content", "text"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "message.complete" -> MessageCompleteEvent(
                    messageId = getString("message_id", "id"),
                    content = getString("content", "text"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "thinking.delta" -> ThinkingDeltaEvent(
                    messageId = getString("message_id", "id"),
                    delta = getString("delta", "text", "chunk"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "reasoning.delta" -> ReasoningDeltaEvent(
                    messageId = getString("message_id", "id"),
                    delta = getString("delta", "text", "chunk"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "reasoning.available" -> ReasoningAvailableEvent(
                    messageId = getString("message_id", "id"),
                    reasoning = getString("reasoning", "content"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "tool.start" -> ToolStartEvent(
                    toolId = getString("tool_id", "id"),
                    name = getString("name", "tool_name"),
                    input = dataObj["input"] ?: root["input"],
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "tool.progress" -> ToolProgressEvent(
                    toolId = getString("tool_id", "id"),
                    progress = getString("progress", "message"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "tool.generating" -> ToolGeneratingEvent(
                    toolId = getString("tool_id", "id"),
                    name = getString("name", "tool_name"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "tool.complete" -> ToolCompleteEvent(
                    toolId = getString("tool_id", "id"),
                    result = getString("result", "output"),
                    isError = getBoolean("is_error", "error"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "approval.request" -> {
                    val choices = getStringList("choices")
                    ApprovalRequestEvent(
                        requestId = getString("request_id", "id"),
                        command = getNullableString("command"),
                        description = getNullableString("description", "prompt"),
                        choices = if (choices.isNotEmpty()) choices else listOf("once", "deny"),
                        sessionKey = sessionKey,
                        sessionId = sessionKey,
                        rawPayload = root
                    )
                }
                "clarify.request" -> ClarifyRequestEvent(
                    requestId = getString("request_id", "id"),
                    questionId = getNullableString("question_id", "questionId"),
                    question = getString("question", "prompt"),
                    promptType = ClarifyType.CLARIFY,
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "sudo.request" -> SudoRequestEvent(
                    requestId = getString("request_id", "id"),
                    question = getString("question", "prompt").ifEmpty { "Administrator password required:" },
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "secret.request" -> SecretRequestEvent(
                    requestId = getString("request_id", "id"),
                    question = getString("question", "prompt").ifEmpty { "Secret / Token required:" },
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "status.update" -> StatusUpdateEvent(
                    status = getString("status"),
                    message = getNullableString("message"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "session.usage" -> SessionUsageEvent(
                    inputTokens = getLong("input_tokens", "prompt_tokens"),
                    outputTokens = getLong("output_tokens", "completion_tokens"),
                    totalTokens = getLong("total_tokens"),
                    sessionId = sessionKey,
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
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "background.complete" -> BackgroundCompleteEvent(
                    taskId = getString("task_id", "id"),
                    result = getNullableString("result"),
                    sessionId = sessionKey,
                    rawPayload = root
                )
                "error" -> ErrorEvent(
                    code = getInt("code"),
                    message = getString("message").ifEmpty { "Unknown error" },
                    sessionId = sessionKey,
                    rawPayload = root
                )
                else -> UnknownGatewayEvent(
                    eventType = eventType.ifEmpty { "unknown" },
                    sessionId = sessionKey,
                    rawPayload = root
                )
            }
        }
    }
}

