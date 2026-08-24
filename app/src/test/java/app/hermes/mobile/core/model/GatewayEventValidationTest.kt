package app.hermes.mobile.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEventValidationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun testSessionIdJsonNullParsesAsNullNotStringNull() {
        val raw = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "message.delta",
                    "session_id": null,
                    "payload": {
                        "message_id": "m1",
                        "delta": "hello"
                    }
                }
            }
        """.trimIndent()
        val root = json.decodeFromString<JsonObject>(raw)
        val event = GatewayEvent.parse(root)

        assertNotNull(event)
        assertTrue(event is GatewayEvent.MessageDeltaEvent)
        val delta = event as GatewayEvent.MessageDeltaEvent
        assertNull("sessionId should be null, not string 'null'", delta.sessionId)
        assertEquals("m1", delta.messageId)
        assertEquals("hello", delta.delta)
    }

    @Test
    fun testNonPrimitiveFieldsDoNotCrashParser() {
        // Object instead of string in delta / messageId, or array in place of primitive
        val rawWithNestedObjects = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "message.delta",
                    "session_id": {"nested": "obj"},
                    "payload": {
                        "message_id": "m1",
                        "delta": {"malformed": [1, 2, 3]}
                    }
                }
            }
        """.trimIndent()
        val root1 = json.decodeFromString<JsonObject>(rawWithNestedObjects)
        val event1 = GatewayEvent.parse(root1)
        assertNotNull(event1)
        assertTrue(event1 is GatewayEvent.MessageDeltaEvent)
        val delta1 = event1 as GatewayEvent.MessageDeltaEvent
        assertNull("Non-primitive session_id should safely resolve to null", delta1.sessionId)
        assertEquals("", delta1.delta)

        val rawWithArrayInIntField = """
            {
                "jsonrpc": "2.0",
                "method": "event",
                "params": {
                    "type": "error",
                    "payload": {
                        "code": [500],
                        "message": "some error"
                    }
                }
            }
        """.trimIndent()
        val root2 = json.decodeFromString<JsonObject>(rawWithArrayInIntField)
        val event2 = GatewayEvent.parse(root2)
        assertNotNull(event2)
        assertTrue(event2 is GatewayEvent.ErrorEvent)
    }

    @Test
    fun testMissingRequiredIdsAreRejected() {
        // 1. message.start without message_id -> null
        val noMsgIdStart = json.decodeFromString<JsonObject>("""{"params":{"type":"message.start","payload":{"role":"assistant"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdStart))

        // 2. message.delta without message_id -> null
        val noMsgIdDelta = json.decodeFromString<JsonObject>("""{"params":{"type":"message.delta","payload":{"delta":"hi"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdDelta))

        // 3. message.complete without message_id -> null
        val noMsgIdComplete = json.decodeFromString<JsonObject>("""{"params":{"type":"message.complete","payload":{"content":"done"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdComplete))

        // 4. thinking.delta without message_id -> null
        val noMsgIdThinking = json.decodeFromString<JsonObject>("""{"params":{"type":"thinking.delta","payload":{"delta":"thinking"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdThinking))

        // 5. reasoning.delta without message_id -> null
        val noMsgIdReasoning = json.decodeFromString<JsonObject>("""{"params":{"type":"reasoning.delta","payload":{"delta":"reasoning"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdReasoning))

        // 6. reasoning.available without message_id -> null
        val noMsgIdReasoningAvail = json.decodeFromString<JsonObject>("""{"params":{"type":"reasoning.available","payload":{"reasoning":"ready"}}}""")
        assertNull(GatewayEvent.parse(noMsgIdReasoningAvail))

        // 7. tool.start without tool_id -> null
        val noToolIdStart = json.decodeFromString<JsonObject>("""{"params":{"type":"tool.start","payload":{"name":"bash"}}}""")
        assertNull(GatewayEvent.parse(noToolIdStart))

        // 8. tool.progress without tool_id -> null
        val noToolIdProgress = json.decodeFromString<JsonObject>("""{"params":{"type":"tool.progress","payload":{"progress":"working"}}}""")
        assertNull(GatewayEvent.parse(noToolIdProgress))

        // 9. tool.generating without tool_id -> null
        val noToolIdGen = json.decodeFromString<JsonObject>("""{"params":{"type":"tool.generating","payload":{"name":"bash"}}}""")
        assertNull(GatewayEvent.parse(noToolIdGen))

        // 10. tool.complete without tool_id -> null
        val noToolIdComplete = json.decodeFromString<JsonObject>("""{"params":{"type":"tool.complete","payload":{"result":"ok"}}}""")
        assertNull(GatewayEvent.parse(noToolIdComplete))

        // 11. approval.request without request_id -> null
        val noReqIdApproval = json.decodeFromString<JsonObject>("""{"params":{"type":"approval.request","payload":{"command":"ls"}}}""")
        assertNull(GatewayEvent.parse(noReqIdApproval))

        // 12. clarify.request without request_id -> null
        val noReqIdClarify = json.decodeFromString<JsonObject>("""{"params":{"type":"clarify.request","payload":{"question":"port?"}}}""")
        assertNull(GatewayEvent.parse(noReqIdClarify))

        // 13. sudo.request without request_id -> null
        val noReqIdSudo = json.decodeFromString<JsonObject>("""{"params":{"type":"sudo.request","payload":{"question":"password"}}}""")
        assertNull(GatewayEvent.parse(noReqIdSudo))

        // 14. secret.request without request_id -> null
        val noReqIdSecret = json.decodeFromString<JsonObject>("""{"params":{"type":"secret.request","payload":{"question":"api key"}}}""")
        assertNull(GatewayEvent.parse(noReqIdSecret))
    }

    @Test
    fun testValidEventAfterRejectedEventParsesNormally() {
        val corrupted = json.decodeFromString<JsonObject>("""{"params":{"type":"message.delta","payload":{"delta":"corrupted"}}}""")
        val rejectedEvent = GatewayEvent.parse(corrupted)
        assertNull(rejectedEvent)

        val valid = json.decodeFromString<JsonObject>("""{"params":{"type":"message.delta","session_id":"s1","payload":{"message_id":"m2","delta":"valid"}}}""")
        val validEvent = GatewayEvent.parse(valid)
        assertNotNull(validEvent)
        assertTrue(validEvent is GatewayEvent.MessageDeltaEvent)
        val delta = validEvent as GatewayEvent.MessageDeltaEvent
        assertEquals("m2", delta.messageId)
        assertEquals("valid", delta.delta)
        assertEquals("s1", delta.sessionId)
    }
}
