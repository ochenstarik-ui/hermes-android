package app.hermes.mobile.core.network

import app.hermes.mobile.core.model.CreateSessionResult
import app.hermes.mobile.core.model.DurableSessionId
import app.hermes.mobile.core.model.GatewayEvent
import app.hermes.mobile.core.model.JsonRpcError
import app.hermes.mobile.core.model.JsonRpcException
import app.hermes.mobile.core.model.JsonRpcRequest
import app.hermes.mobile.core.model.JsonRpcResponse
import app.hermes.mobile.core.model.PromptSubmitResult
import app.hermes.mobile.core.model.ResumeSessionResult
import app.hermes.mobile.core.model.RuntimeSessionId
import app.hermes.mobile.core.model.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Reconnecting(val attempt: Int) : ConnectionState()
    data class Failed(val error: Throwable) : ConnectionState()
    data class AuthExpired(val message: String = "Session expired. Please sign in again.") : ConnectionState()
}

class JsonRpcGatewayClient(
    val client: OkHttpClient = defaultClient(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        fun defaultClient(certificateFingerprint: String? = null): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets
                .pingInterval(30, TimeUnit.SECONDS)
            return TlsFingerprintTrust.configureClient(builder, certificateFingerprint).build()
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val logger = Logger.getLogger(JsonRpcGatewayClient::class.java.name)
    private val droppedFramesCounter = AtomicInteger(0)
    val droppedFrames: Int get() = droppedFramesCounter.get()

    private val reqCounter = AtomicInteger(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonRpcResponse>>()
    private val stateLock = Any()
    private var gatewayReadyDeferred = CompletableDeferred<Unit>()

    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var currentListener: WebSocketListener? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val eventQueue = ConcurrentLinkedQueue<GatewayEvent>()
    private val isProcessingEvents = AtomicBoolean(false)

    private fun dispatchEvent(event: GatewayEvent) {
        eventQueue.add(event)
        drainEventQueue()
    }

    private fun drainEventQueue() {
        if (isProcessingEvents.compareAndSet(false, true)) {
            scope.launch {
                try {
                    while (true) {
                        val next = eventQueue.poll() ?: break
                        _events.emit(next)
                    }
                } finally {
                    isProcessingEvents.set(false)
                    if (!eventQueue.isEmpty()) {
                        drainEventQueue()
                    }
                }
            }
        }
    }

    @Volatile
    private var closeLatch: java.util.concurrent.CountDownLatch? = null

    private fun nextId(): String = "a${reqCounter.incrementAndGet()}"

    fun connect(wsUrl: String, ticket: String? = null, allowCleartext: Boolean = false) {
        if (!allowCleartext && wsUrl.startsWith("ws://", ignoreCase = true)) {
            _connectionState.value = ConnectionState.Failed(
                SecurityException("Cleartext WebSocket is not allowed unless explicitly permitted in connection settings.")
            )
            return
        }

        val oldWs: WebSocket?
        val oldLatch: java.util.concurrent.CountDownLatch?
        synchronized(stateLock) {
            currentListener = null
            oldWs = activeWebSocket
            oldLatch = closeLatch
            activeWebSocket = null
            if (!gatewayReadyDeferred.isCompleted) {
                gatewayReadyDeferred.completeExceptionally(IOException("Replaced by new connection"))
            }
            gatewayReadyDeferred = CompletableDeferred()
            closeLatch = java.util.concurrent.CountDownLatch(1)
        }
        
        if (oldWs != null) {
            try {
                oldWs.close(1000, "Replaced by new connection")
            } catch (_: Exception) {}
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (oldLatch?.await(2, java.util.concurrent.TimeUnit.SECONDS) == false) {
                        oldWs.cancel()
                    }
                } catch (_: Exception) {
                    oldWs.cancel()
                }
            }
        }

        _connectionState.value = ConnectionState.Connecting

        val requestBuilder = Request.Builder().url(wsUrl)
        if (!ticket.isNullOrEmpty()) {
            requestBuilder.header("Authorization", "Bearer $ticket")
        }
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this !== currentListener) return
                synchronized(stateLock) {
                    activeWebSocket = webSocket
                }
                _connectionState.value = ConnectionState.Connecting
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this !== currentListener || webSocket !== activeWebSocket) return
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    webSocket.close(code, reason)
                } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closeLatch?.countDown()
                synchronized(stateLock) {
                    if (this !== currentListener) return
                    _connectionState.value = ConnectionState.Disconnected
                    if (!gatewayReadyDeferred.isCompleted) {
                        gatewayReadyDeferred.completeExceptionally(IOException("WebSocket closed: $code $reason"))
                    }
                }
                failPendingRequests(IOException("WebSocket closed: $code $reason"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closeLatch?.countDown()
                synchronized(stateLock) {
                    if (this !== currentListener) return
                    _connectionState.value = ConnectionState.Failed(t)
                    if (!gatewayReadyDeferred.isCompleted) {
                        gatewayReadyDeferred.completeExceptionally(t)
                    }
                }
                failPendingRequests(t)
            }
        }

        synchronized(stateLock) {
            currentListener = listener
        }
        val newWs = client.newWebSocket(request, listener)
        synchronized(stateLock) {
            if (currentListener === listener) {
                activeWebSocket = newWs
            }
        }
    }

    suspend fun awaitGatewayReady(timeoutMs: Long = 10_000) {
        if (_connectionState.value is ConnectionState.Connected) return
        val deferred = synchronized(stateLock) {
            if (_connectionState.value is ConnectionState.Connected) return
            gatewayReadyDeferred
        }
        withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    fun setAuthExpired(message: String = "Session expired. Please sign in again.") {
        _connectionState.value = ConnectionState.AuthExpired(message)
        synchronized(stateLock) {
            if (!gatewayReadyDeferred.isCompleted) {
                gatewayReadyDeferred.completeExceptionally(IOException(message))
            }
        }
        failPendingRequests(IOException(message))
    }

    fun disconnect() {
        val ws: WebSocket?
        val latch: java.util.concurrent.CountDownLatch?
        synchronized(stateLock) {
            ws = activeWebSocket
            latch = closeLatch
            activeWebSocket = null
            currentListener = null
            if (!gatewayReadyDeferred.isCompleted) {
                gatewayReadyDeferred.completeExceptionally(IOException("Client disconnected"))
            }
        }

        if (ws != null) {
            try {
                ws.close(1000, "Client initiated disconnect")
            } catch (_: Exception) {}
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (latch?.await(2, java.util.concurrent.TimeUnit.SECONDS) == false) {
                        ws.cancel()
                    }
                } catch (_: Exception) {
                    ws.cancel()
                }
            }
        }
        try {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        } catch (_: Exception) {}
        _connectionState.value = ConnectionState.Disconnected
        failPendingRequests(IOException("Client disconnected"))
    }

    private fun failPendingRequests(t: Throwable) {
        for ((_, deferred) in pendingRequests) {
            deferred.completeExceptionally(t)
        }
        pendingRequests.clear()
    }

    fun handleIncomingMessage(text: String) {
        try {
            val root = json.decodeFromString<JsonObject>(text)

            // 1. Is this a JSON-RPC response with id matching pending request?
            val id = root["id"]?.jsonPrimitive?.content
            if (!id.isNullOrEmpty() && pendingRequests.containsKey(id)) {
                val deferred = pendingRequests.remove(id)
                val response = try {
                    json.decodeFromString<JsonRpcResponse>(text)
                } catch (e: Exception) {
                    val isErr = root.containsKey("error")
                    if (isErr) {
                        JsonRpcResponse(
                            jsonrpc = "2.0",
                            id = id,
                            error = JsonRpcError(
                                code = -32000,
                                message = root["error"]?.toString() ?: "Unknown error"
                            )
                        )
                    } else {
                        JsonRpcResponse(jsonrpc = "2.0", id = id, result = root["result"])
                    }
                }
                deferred?.complete(response)
                return
            }

            // 2. Otherwise, treat as Gateway Event / Notification
            val event = GatewayEvent.parse(root) ?: run {
                val count = droppedFramesCounter.incrementAndGet()
                logger.warning("Dropped invalid or unparseable gateway event frame #$count")
                return
            }
            if (event is GatewayEvent.GatewayReadyEvent) {
                _connectionState.value = ConnectionState.Connected
                synchronized(stateLock) {
                    if (!gatewayReadyDeferred.isCompleted) {
                        gatewayReadyDeferred.complete(Unit)
                    }
                }
            }
            dispatchEvent(event)
        } catch (e: Exception) {
            val count = droppedFramesCounter.incrementAndGet()
            logger.warning("Dropped corrupted incoming frame #$count: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun sendRequest(
        method: String,
        params: JsonObject = buildJsonObject {},
        timeoutMs: Long = 120_000
    ): JsonRpcResponse {
        val ws = activeWebSocket ?: throw IOException("WebSocket is not connected")
        val reqId = nextId()
        val request = JsonRpcRequest(id = reqId, method = method, params = params)
        val jsonString = json.encodeToString(request)

        val deferred = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[reqId] = deferred

        return try {
            val sent = ws.send(jsonString)
            if (!sent) {
                pendingRequests.remove(reqId)
                throw IOException("Failed to send message over WebSocket")
            }
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: Exception) {
            pendingRequests.remove(reqId)
            throw e
        }
    }

    suspend fun listSessions(limit: Int = 200): List<SessionSummary> {
        val params = buildJsonObject { put("limit", limit) }
        val response = sendRequest("session.list", params)
        if (response.error != null) {
            throw JsonRpcException(response.error.code, response.error.message, response.error.data)
        }
        val result = response.result ?: return emptyList()

        val list = mutableListOf<SessionSummary>()
        val sessionsArray = when (result) {
            is JsonArray -> result
            is JsonObject -> result["sessions"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        for (item in sessionsArray) {
            if (item is JsonObject) {
                val durableVal = item["stored_session_id"]?.jsonPrimitive?.content
                    ?: item["durable_id"]?.jsonPrimitive?.content
                    ?: item["id"]?.jsonPrimitive?.content
                    ?: item["durable_session_id"]?.jsonPrimitive?.content
                    ?: ""
                if (durableVal.isNotEmpty()) {
                    list.add(
                        SessionSummary(
                            id = DurableSessionId(durableVal),
                            title = item["title"]?.jsonPrimitive?.content ?: "Session ${durableVal.take(8)}",
                            preview = item["preview"]?.jsonPrimitive?.content ?: "",
                            startedAt = item["started_at"]?.jsonPrimitive?.longOrNull
                                ?: item["createdAt"]?.jsonPrimitive?.longOrNull
                                ?: System.currentTimeMillis(),
                            messageCount = item["message_count"]?.jsonPrimitive?.intOrNull ?: 0,
                            source = item["source"]?.jsonPrimitive?.content ?: "android"
                        )
                    )
                }
            }
        }
        return list
    }

    suspend fun createSession(cols: Int = 100, source: String = "android"): CreateSessionResult {
        val params = buildJsonObject {
            put("cols", cols)
            put("source", source)
        }
        val response = sendRequest("session.create", params)
        if (response.error != null) {
            throw JsonRpcException(response.error.code, response.error.message, response.error.data)
        }
        val result = response.result as? JsonObject
            ?: throw IOException("Invalid response format for session.create")

        val durable = result["stored_session_id"]?.jsonPrimitive?.content
            ?: result["durable_id"]?.jsonPrimitive?.content
            ?: result["durable_session_id"]?.jsonPrimitive?.content
            ?: result["id"]?.jsonPrimitive?.content
            ?: result["session_id"]?.jsonPrimitive?.content
            ?: throw IOException("Missing stored_session_id/durable_id in session.create result")

        val runtime = result["session_id"]?.jsonPrimitive?.content
            ?: result["runtime_id"]?.jsonPrimitive?.content
            ?: result["runtime_session_id"]?.jsonPrimitive?.content
            ?: durable

        return CreateSessionResult(
            durableId = DurableSessionId(durable),
            runtimeId = RuntimeSessionId(runtime)
        )
    }

    suspend fun resumeSession(durableId: DurableSessionId, source: String = "android"): ResumeSessionResult {
        val params = buildJsonObject {
            put("session_id", durableId.value)
            put("source", source)
        }
        val response = sendRequest("session.resume", params)
        if (response.error != null) {
            throw JsonRpcException(response.error.code, response.error.message, response.error.data)
        }
        val result = response.result as? JsonObject
            ?: throw IOException("Invalid response format for session.resume")

        val durable = result["stored_session_id"]?.jsonPrimitive?.content
            ?: result["durable_id"]?.jsonPrimitive?.content
            ?: result["durable_session_id"]?.jsonPrimitive?.content
            ?: durableId.value

        val runtime = result["session_id"]?.jsonPrimitive?.content
            ?: result["runtime_id"]?.jsonPrimitive?.content
            ?: result["runtime_session_id"]?.jsonPrimitive?.content
            ?: durable

        return ResumeSessionResult(
            durableId = DurableSessionId(durable),
            runtimeId = RuntimeSessionId(runtime)
        )
    }

    suspend fun submitPrompt(runtimeId: RuntimeSessionId, text: String, contextPreamble: String? = null): PromptSubmitResult {
        val params = buildJsonObject {
            put("session_id", runtimeId.value)
            put("text", text)
            if (contextPreamble != null) {
                put("context_preamble", contextPreamble)
            }
        }
        val response = sendRequest("prompt.submit", params)
        if (response.error != null) {
            throw JsonRpcException(response.error.code, response.error.message, response.error.data)
        }
        val result = response.result as? JsonObject
        val turnId = result?.get("turn_id")?.jsonPrimitive?.content
            ?: result?.get("turnId")?.jsonPrimitive?.content
        return PromptSubmitResult(turnId = turnId, accepted = true)
    }

    suspend fun interruptSession(runtimeId: RuntimeSessionId): Boolean {
        val params = buildJsonObject {
            put("session_id", runtimeId.value)
        }
        val response = sendRequest("session.interrupt", params)
        return response.error == null
    }

    suspend fun respondApproval(
        sessionKey: String,
        requestId: String,
        choice: String,
        all: Boolean = false
    ): Boolean {
        val params = buildJsonObject {
            put("session_id", sessionKey)
            put("request_id", requestId)
            put("choice", choice)
            put("all", all)
        }
        val response = sendRequest("approval.respond", params)
        return response.error == null
    }

    suspend fun respondClarify(
        requestId: String,
        answer: String,
        questionId: String? = null
    ): Boolean {
        val params = buildJsonObject {
            put("request_id", requestId)
            put("answer", answer)
            if (!questionId.isNullOrEmpty()) {
                put("question_id", questionId)
            }
        }
        val response = sendRequest("clarify.respond", params)
        return response.error == null
    }

    suspend fun respondSudo(requestId: String, password: String): Boolean {
        val params = buildJsonObject {
            put("request_id", requestId)
            put("password", password)
        }
        val response = sendRequest("sudo.respond", params)
        return response.error == null
    }

    suspend fun respondSecret(requestId: String, value: String): Boolean {
        val params = buildJsonObject {
            put("request_id", requestId)
            put("value", value)
        }
        val response = sendRequest("secret.respond", params)
        return response.error == null
    }
}
