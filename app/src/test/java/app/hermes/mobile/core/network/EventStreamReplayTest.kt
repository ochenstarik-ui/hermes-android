package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.GatewayEvent.MessageDeltaEvent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStreamReplayTest {

    @Test
    fun testNoReplay() = runTest {
        val client = JsonRpcGatewayClient(scope = this)
        
        // Emit first event BEFORE subscribing
        client.handleIncomingMessage("{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"session_id\":\"s1\",\"payload\":{\"message_id\":\"m1\",\"delta\":\"Hello\"}}}")
        runCurrent()
        
        val events = mutableListOf<GatewayEvent>()
        val job = launch {
            client.events.collect { events.add(it) }
        }
        runCurrent()
        
        // Should not have received the first event
        assertTrue("Events should be empty since we subscribed after the first emission", events.isEmpty())
        
        // Emit second event AFTER subscribing
        client.handleIncomingMessage("{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"session_id\":\"s1\",\"payload\":{\"message_id\":\"m1\",\"delta\":\" World\"}}}")
        runCurrent()
        
        assertEquals("Should have exactly 1 event", 1, events.size)
        val event = events[0] as GatewayEvent.MessageDeltaEvent
        assertEquals(" World", event.delta)
        
        job.cancel()
    }
}
