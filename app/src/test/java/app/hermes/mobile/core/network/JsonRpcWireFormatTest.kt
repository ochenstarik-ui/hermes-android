package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.ClarifyType
import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.JsonRpcError
import app.hermes.mobile.core.model.JsonRpcRequest
import app.hermes.mobile.core.model.JsonRpcResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Test
    fun testRequestSerialization() {
        val request = JsonRpcRequest(
            id = "a1",
            method = "session.create",
            params = buildJsonObject {
                put("source", "android")
                put("cols", 100)
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)

        assertEquals("2.0", parsed["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("a1", parsed["id"]?.jsonPrimitive?.content)
        assertEquals("session.create", parsed["method"]?.jsonPrimitive?.content)
    }

    @Test
    fun testResponseDeserializationSuccess() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "id": "a1",
                "result": {
                    "durable_id": "sess_12345",
                    "runtime_id": "rt_67890"
                }
            }
        """.trimIndent()
        val response = json.decodeFromString<JsonRpcResponse>(raw)

        assertEquals("2.0", response.jsonrpc)
        assertEquals("a1", response.id)
        assertNotNull(response.result)
        assertNull(response.error)
    }

    @Test
    fun testResponseDeserializationError() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "id": "a2",
                "error": {
                    "code": -32601,
                    "message": "Method not found"
                }
            }
        """.trimIndent()
        val response = json.decodeFromString<JsonRpcResponse>(raw)

        assertEquals("a2", response.id)
        assertNotNull(response.error)
        assertEquals(-32601, response.error?.code)
        assertEquals("Method not found", response.error?.message)
    }

    @Test
    fun testAllGatewayEventParsers() {
        // 1. Gateway Ready
        val readyJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"version":"1.0.0","session_count":3}}}""")
        val readyEvent = GatewayEvent.parse(readyJson) as GatewayEvent.GatewayReadyEvent
        assertEquals("1.0.0", readyEvent.version)
        assertEquals(3, readyEvent.sessionCount)

        // 2. Message Start
        val msgStartJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"rt_test","payload":{"message_id":"msg_1","role":"assistant"}}}""")
        val msgStart = GatewayEvent.parse(msgStartJson) as GatewayEvent.MessageStartEvent
        assertEquals("msg_1", msgStart.messageId)
        assertEquals("assistant", msgStart.role)
        assertEquals("rt_test", msgStart.sessionId)

        // 3. Message Delta
        val msgDeltaJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"rt_test","payload":{"message_id":"msg_1","delta":"Hello world"}}}""")
        val msgDelta = GatewayEvent.parse(msgDeltaJson) as GatewayEvent.MessageDeltaEvent
        assertEquals("msg_1", msgDelta.messageId)
        assertEquals("Hello world", msgDelta.delta)
        assertEquals("rt_test", msgDelta.sessionId)

        // 4. Message Complete
        val msgCompleteJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"rt_test","payload":{"message_id":"msg_1","content":"Final answer"}}}""")
        val msgComplete = GatewayEvent.parse(msgCompleteJson) as GatewayEvent.MessageCompleteEvent
        assertEquals("msg_1", msgComplete.messageId)
        assertEquals("Final answer", msgComplete.content)
        assertEquals("rt_test", msgComplete.sessionId)

        // 5. Thinking Delta
        val thinkJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"thinking.delta","session_id":"rt_test","payload":{"message_id":"msg_1","delta":"Analyzing requirements..."}}}""")
        val think = GatewayEvent.parse(thinkJson) as GatewayEvent.ThinkingDeltaEvent
        assertEquals("Analyzing requirements...", think.delta)
        assertEquals("rt_test", think.sessionId)

        // 6. Tool Lifecycle
        val toolStartJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"rt_test","payload":{"tool_id":"t1","name":"exec_command"}}}""")
        val toolStart = GatewayEvent.parse(toolStartJson) as GatewayEvent.ToolStartEvent
        assertEquals("t1", toolStart.toolId)
        assertEquals("exec_command", toolStart.name)
        assertEquals("rt_test", toolStart.sessionId)

        val toolProgressJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.progress","session_id":"rt_test","payload":{"tool_id":"t1","progress":"Running build..."}}}""")
        val toolProgress = GatewayEvent.parse(toolProgressJson) as GatewayEvent.ToolProgressEvent
        assertEquals("Running build...", toolProgress.progress)
        assertEquals("rt_test", toolProgress.sessionId)

        val toolCompleteJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"rt_test","payload":{"tool_id":"t1","result":"Success","is_error":false}}}""")
        val toolComplete = GatewayEvent.parse(toolCompleteJson) as GatewayEvent.ToolCompleteEvent
        assertEquals("Success", toolComplete.result)
        assertEquals(false, toolComplete.isError)
        assertEquals("rt_test", toolComplete.sessionId)

        // 7. Approval Request
        val approvalJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"rt_test","payload":{"request_id":"req_app","command":"rm -rf /tmp/cache","description":"Clear cache directory","choices":["once","deny"]}}}""")
        val approval = GatewayEvent.parse(approvalJson) as GatewayEvent.ApprovalRequestEvent
        assertEquals("req_app", approval.requestId)
        assertEquals("rm -rf /tmp/cache", approval.command)
        assertEquals(2, approval.choices.size)
        assertEquals("rt_test", approval.sessionId)

        // 8. Clarify, Sudo, Secret
        val clarifyJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"rt_test","payload":{"request_id":"c1","question":"Which port?"}}}""")
        val clarify = GatewayEvent.parse(clarifyJson) as GatewayEvent.ClarifyRequestEvent
        assertEquals("Which port?", clarify.question)
        assertEquals(ClarifyType.CLARIFY, clarify.promptType)
        assertEquals("rt_test", clarify.sessionId)

        val sudoJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"sudo.request","session_id":"rt_test","payload":{"request_id":"s1","question":"Root password required:"}}}""")
        val sudo = GatewayEvent.parse(sudoJson) as GatewayEvent.SudoRequestEvent
        assertEquals("Root password required:", sudo.question)
        assertEquals("rt_test", sudo.sessionId)

        val secretJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"secret.request","session_id":"rt_test","payload":{"request_id":"sec1","question":"OpenAI API Key:"}}}""")
        val secret = GatewayEvent.parse(secretJson) as GatewayEvent.SecretRequestEvent
        assertEquals("OpenAI API Key:", secret.question)
        assertEquals("rt_test", secret.sessionId)

        // 9. Session Info & Usage
        val infoJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"rt_test","payload":{"model":"claude-3-5-sonnet","provider":"anthropic","branch":"main"}}}""")
        val info = GatewayEvent.parse(infoJson) as GatewayEvent.SessionInfoEvent
        assertEquals("claude-3-5-sonnet", info.info.model)
        assertEquals("main", info.info.branch)
        assertEquals("rt_test", info.sessionId)

        val usageJson = json.decodeFromString<JsonObject>("""{"jsonrpc":"2.0","method":"event","params":{"type":"session.usage","session_id":"rt_test","payload":{"input_tokens":1200,"output_tokens":350,"total_tokens":1550}}}""")
        val usage = GatewayEvent.parse(usageJson) as GatewayEvent.SessionUsageEvent
        assertEquals(1200L, usage.inputTokens)
        assertEquals(350L, usage.outputTokens)
        assertEquals(1550L, usage.totalTokens)
        assertEquals("rt_test", usage.sessionId)
    }

    @Test
    fun testUpstreamHermesContractMessageStart() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "message.start",
                    "session_id": "rt-session-001",
                    "payload": {
                        "message_id": "msg-start-001",
                        "role": "assistant"
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.MessageStartEvent)
        val startEvent = event as GatewayEvent.MessageStartEvent
        assertEquals("msg-start-001", startEvent.messageId)
        assertEquals("assistant", startEvent.role)
        assertEquals("rt-session-001", startEvent.sessionId)
    }

    @Test
    fun testUpstreamHermesContractMessageDelta() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "message.delta",
                    "session_id": "rt-session-001",
                    "payload": {
                        "message_id": "msg-delta-001",
                        "delta": "Hello from Hermes"
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.MessageDeltaEvent)
        val deltaEvent = event as GatewayEvent.MessageDeltaEvent
        assertEquals("msg-delta-001", deltaEvent.messageId)
        assertEquals("Hello from Hermes", deltaEvent.delta)
        assertEquals("rt-session-001", deltaEvent.sessionId)
    }

    @Test
    fun testUpstreamHermesContractMessageComplete() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "message.complete",
                    "session_id": "rt-session-001",
                    "payload": {
                        "message_id": "msg-complete-001",
                        "content": "Execution completed successfully."
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.MessageCompleteEvent)
        val completeEvent = event as GatewayEvent.MessageCompleteEvent
        assertEquals("msg-complete-001", completeEvent.messageId)
        assertEquals("Execution completed successfully.", completeEvent.content)
        assertEquals("rt-session-001", completeEvent.sessionId)
    }

    @Test
    fun testUpstreamHermesContractToolStart() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "tool.start",
                    "session_id": "rt-session-001",
                    "payload": {
                        "tool_id": "tool-call-101",
                        "name": "bash_execution",
                        "input": {
                            "command": "ls -la"
                        }
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.ToolStartEvent)
        val toolStart = event as GatewayEvent.ToolStartEvent
        assertEquals("tool-call-101", toolStart.toolId)
        assertEquals("bash_execution", toolStart.name)
        assertNotNull(toolStart.input)
        assertEquals("rt-session-001", toolStart.sessionId)
    }

    @Test
    fun testUpstreamHermesContractToolProgress() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "tool.progress",
                    "session_id": "rt-session-001",
                    "payload": {
                        "tool_id": "tool-call-101",
                        "progress": "Downloading dependencies..."
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.ToolProgressEvent)
        val toolProgress = event as GatewayEvent.ToolProgressEvent
        assertEquals("tool-call-101", toolProgress.toolId)
        assertEquals("Downloading dependencies...", toolProgress.progress)
        assertEquals("rt-session-001", toolProgress.sessionId)
    }

    @Test
    fun testUpstreamHermesContractToolComplete() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "tool.complete",
                    "session_id": "rt-session-001",
                    "payload": {
                        "tool_id": "tool-call-101",
                        "result": "Total 14 files found",
                        "is_error": false
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.ToolCompleteEvent)
        val toolComplete = event as GatewayEvent.ToolCompleteEvent
        assertEquals("tool-call-101", toolComplete.toolId)
        assertEquals("Total 14 files found", toolComplete.result)
        assertEquals(false, toolComplete.isError)
        assertEquals("rt-session-001", toolComplete.sessionId)
    }

    @Test
    fun testUpstreamHermesContractApprovalRequest() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "approval.request",
                    "session_id": "rt-session-001",
                    "payload": {
                        "request_id": "appr-req-999",
                        "command": "rm -rf /var/log/*",
                        "description": "Clean logs directory",
                        "choices": ["once", "deny", "always"]
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.ApprovalRequestEvent)
        val approval = event as GatewayEvent.ApprovalRequestEvent
        assertEquals("appr-req-999", approval.requestId)
        assertEquals("rm -rf /var/log/*", approval.command)
        assertEquals("Clean logs directory", approval.description)
        assertEquals(3, approval.choices.size)
        assertEquals(listOf("once", "deny", "always"), approval.choices)
        assertEquals("rt-session-001", approval.sessionId)
        assertEquals("rt-session-001", approval.sessionKey)
    }

    @Test
    fun testUpstreamHermesContractClarifyRequest() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "clarify.request",
                    "session_id": "rt-session-001",
                    "payload": {
                        "request_id": "clarify-req-555",
                        "question_id": "q-port-target",
                        "question": "Which HTTP port should the mock server bind to?"
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)
        assertTrue(event is GatewayEvent.ClarifyRequestEvent)
        val clarify = event as GatewayEvent.ClarifyRequestEvent
        assertEquals("clarify-req-555", clarify.requestId)
        assertEquals("q-port-target", clarify.questionId)
        assertEquals("Which HTTP port should the mock server bind to?", clarify.question)
        assertEquals(ClarifyType.CLARIFY, clarify.promptType)
        assertEquals("rt-session-001", clarify.sessionId)
    }

    @Test
    fun testUnknownEventToleranceWithoutCrash() {
        val rawUnknown = """
            {
                "event": "custom.future.event.v99",
                "data": {
                    "some_new_field": 42
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(rawUnknown)
        val event = GatewayEvent.parse(root)

        assertTrue(event is GatewayEvent.UnknownGatewayEvent)
        val unknown = event as GatewayEvent.UnknownGatewayEvent
        assertEquals("custom.future.event.v99", unknown.eventType)
    }

    @Test
    fun testPromptSubmitWireParams() {
        val request = JsonRpcRequest(
            id = "a10",
            method = "prompt.submit",
            params = buildJsonObject {
                put("session_id", "rt_session_123")
                put("text", "Execute query")
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)
        val params = parsed["params"] as JsonObject

        assertEquals("rt_session_123", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("Execute query", params["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSessionResumeWireParams() {
        val request = JsonRpcRequest(
            id = "a11",
            method = "session.resume",
            params = buildJsonObject {
                put("session_id", "durable_session_456")
                put("source", "android")
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)
        val params = parsed["params"] as JsonObject

        assertEquals("durable_session_456", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("android", params["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSessionInterruptWireParams() {
        val request = JsonRpcRequest(
            id = "a12",
            method = "session.interrupt",
            params = buildJsonObject {
                put("session_id", "rt_session_123")
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)
        val params = parsed["params"] as JsonObject

        assertEquals("rt_session_123", params["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun testApprovalRespondWireParams() {
        val request = JsonRpcRequest(
            id = "a13",
            method = "approval.respond",
            params = buildJsonObject {
                put("session_id", "rt_session_123")
                put("request_id", "app_req_99")
                put("choice", "once")
                put("all", false)
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)
        val params = parsed["params"] as JsonObject

        assertEquals("rt_session_123", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("app_req_99", params["request_id"]?.jsonPrimitive?.content)
        assertEquals("once", params["choice"]?.jsonPrimitive?.content)
        assertEquals("false", params["all"]?.jsonPrimitive?.content)
    }

    @Test
    fun testClarifyRespondWireParamsWithQuestionId() {
        val request = JsonRpcRequest(
            id = "a14",
            method = "clarify.respond",
            params = buildJsonObject {
                put("request_id", "c1")
                put("answer", "port 8080")
                put("question_id", "q_port")
            }
        )
        val serialized = json.encodeToString(request)
        val parsed = json.decodeFromString<JsonObject>(serialized)
        val params = parsed["params"] as JsonObject

        assertEquals("c1", params["request_id"]?.jsonPrimitive?.content)
        assertEquals("port 8080", params["answer"]?.jsonPrimitive?.content)
        assertEquals("q_port", params["question_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun testSessionCreateResponseWithStoredSessionId() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "id": "a15",
                "result": {
                    "stored_session_id": "dur_sess_999",
                    "session_id": "rt_sess_888"
                }
            }
        """.trimIndent()
        val response = json.decodeFromString<JsonRpcResponse>(raw)
        val result = response.result as JsonObject

        assertEquals("dur_sess_999", result["stored_session_id"]?.jsonPrimitive?.content)
        assertEquals("rt_sess_888", result["session_id"]?.jsonPrimitive?.content)
    }
}
