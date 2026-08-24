package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.model.HostGatewayEvent
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesHttpException
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

class HermesHostRuntime(
    initialHost: HermesHost,
    val restClient: HermesRestClient = HermesRestClient.forHost(initialHost.certificateFingerprint),
    val tokenVault: TokenVault,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val gatewayClient: JsonRpcGatewayClient = JsonRpcGatewayClient(
        client = JsonRpcGatewayClient.defaultClient(initialHost.certificateFingerprint),
        scope = scope
    )
) {
    private val _host = MutableStateFlow(initialHost)
    val host: StateFlow<HermesHost> = _host.asStateFlow()

    val hostId: HermesHostId get() = _host.value.id

    private val _status = MutableStateFlow(initialHost.lastKnownStatus)
    val status: StateFlow<HostStatus> = _status.asStateFlow()

    private val _serverStatus = MutableStateFlow<HermesServerStatus?>(null)
    val serverStatus: StateFlow<HermesServerStatus?> = _serverStatus.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = gatewayClient.connectionState

    private val _events = MutableSharedFlow<HostGatewayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HostGatewayEvent> = _events.asSharedFlow()

    private val eventQueue = ConcurrentLinkedQueue<HostGatewayEvent>()
    private val isProcessingEvents = AtomicBoolean(false)

    private fun dispatchEvent(event: HostGatewayEvent) {
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

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val reconnectMutex = Mutex()
    private var reconnectJob: Job? = null
    private val autoReconnectEnabled = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)

    fun isAutoReconnectActive(): Boolean = autoReconnectEnabled.get()

    fun getReconnectAttemptCount(): Int = reconnectAttempt.get()

    fun onNetworkRestored() {
        reconnectAttempt.set(0)
        if (_host.value.enabled && (_status.value == HostStatus.ERROR || _status.value == HostStatus.OFFLINE)) {
            autoReconnectEnabled.set(true)
            scheduleReconnect()
        }
    }

    fun onNetworkLost() {
        scope.launch {
            reconnectMutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
            }
        }
        if (_status.value == HostStatus.CONNECTING || _status.value == HostStatus.ONLINE) {
            _status.value = HostStatus.OFFLINE
        }
    }

    init {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            gatewayClient.events.collect { event ->
                _events.emit(HostGatewayEvent(hostId, event))
            }
        }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            gatewayClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        reconnectAttempt.set(0)
                        scope.launch {
                            reconnectMutex.withLock {
                                reconnectJob?.cancel()
                                reconnectJob = null
                            }
                        }
                        _status.value = HostStatus.ONLINE
                        updateLastSeen()
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                        _status.value = HostStatus.CONNECTING
                    }
                    is ConnectionState.AuthExpired -> {
                        autoReconnectEnabled.set(false)
                        scope.launch {
                            reconnectMutex.withLock {
                                reconnectJob?.cancel()
                                reconnectJob = null
                            }
                        }
                        _status.value = HostStatus.AUTH_EXPIRED
                    }
                    is ConnectionState.Failed -> {
                        _status.value = HostStatus.ERROR
                        if (autoReconnectEnabled.get()) {
                            scheduleReconnect()
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (_status.value != HostStatus.AUTH_EXPIRED && _status.value != HostStatus.AUTH_REQUIRED) {
                            _status.value = HostStatus.OFFLINE
                        }
                        if (autoReconnectEnabled.get()) {
                            scheduleReconnect()
                        }
                    }
                }
            }
        }
    }

    fun updateHost(newHost: HermesHost) {
        _host.value = newHost
    }

    private fun updateLastSeen() {
        val updated = _host.value.copy(
            lastSeenAt = System.currentTimeMillis(),
            lastKnownStatus = _status.value
        )
        _host.value = updated
    }

    suspend fun checkStatus(): Result<HermesServerStatus> {
        val currentHost = _host.value
        val result = restClient.getStatus(currentHost.baseUrl, currentHost.allowCleartext)
        if (result.isSuccess) {
            _serverStatus.value = result.getOrNull()
            updateLastSeen()
        }
        return result
    }

    suspend fun connect(): Result<Unit> {
        autoReconnectEnabled.set(true)
        reconnectAttempt.set(0)
        reconnectMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        return connectInternal()
    }

    private suspend fun connectInternal(): Result<Unit> {
        val currentHost = _host.value
        _status.value = HostStatus.CONNECTING

        return try {
            val statusResult = restClient.getStatus(currentHost.baseUrl, currentHost.allowCleartext)
            if (statusResult.isFailure) {
                _status.value = HostStatus.ERROR
                return Result.failure(statusResult.exceptionOrNull() ?: IOException("Failed to check host status"))
            }
            val sStatus = statusResult.getOrThrow()
            _serverStatus.value = sStatus

            var ticket: String? = null
            if (sStatus.authRequired) {
                var tokens = tokenVault.getTokens(currentHost.id.value)
                if (tokens == null) {
                    _status.value = HostStatus.AUTH_REQUIRED
                    return Result.failure(IllegalStateException("Authentication required for ${currentHost.displayName}"))
                }

                val nowSeconds = System.currentTimeMillis() / 1000
                val isExpiring = tokens.expiresAt > 0 && nowSeconds >= (tokens.expiresAt - 60)

                if (isExpiring && tokens.refreshToken.isNotEmpty()) {
                    val refreshRes = restClient.refreshNativeToken(
                        baseUrl = currentHost.baseUrl,
                        refreshToken = tokens.refreshToken,
                        provider = tokens.provider,
                        allowCleartext = currentHost.allowCleartext
                    )
                    if (refreshRes.isSuccess) {
                        val newTokens = refreshRes.getOrThrow()
                        tokenVault.saveTokens(currentHost.id.value, newTokens)
                        tokens = newTokens
                    } else {
                        val refreshEx = refreshRes.exceptionOrNull()
                        val isUnauthorized = (refreshEx is HermesHttpException && refreshEx.statusCode == 401)
                        val errMsg = refreshEx?.message ?: ""
                        if (isUnauthorized || errMsg.contains("session_expired") || errMsg.contains("invalid_grant")) {
                            tokenVault.clearTokens(currentHost.id.value)
                            _status.value = HostStatus.AUTH_EXPIRED
                            gatewayClient.setAuthExpired("Session expired for ${currentHost.displayName}")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                    }
                }

                var ticketResult = restClient.mintWsTicket(
                    baseUrl = currentHost.baseUrl,
                    accessToken = tokens.accessToken,
                    allowCleartext = currentHost.allowCleartext
                )

                if (ticketResult.isFailure) {
                    val ticketEx = ticketResult.exceptionOrNull()
                    val isUnauthorized = (ticketEx is HermesHttpException && ticketEx.statusCode == 401)
                    if (isUnauthorized && tokens.refreshToken.isNotEmpty()) {
                        val refreshRes = restClient.refreshNativeToken(
                            baseUrl = currentHost.baseUrl,
                            refreshToken = tokens.refreshToken,
                            provider = tokens.provider,
                            allowCleartext = currentHost.allowCleartext
                        )
                        if (refreshRes.isSuccess) {
                            val newTokens = refreshRes.getOrThrow()
                            tokenVault.saveTokens(currentHost.id.value, newTokens)
                            tokens = newTokens
                            ticketResult = restClient.mintWsTicket(
                                baseUrl = currentHost.baseUrl,
                                accessToken = tokens.accessToken,
                                allowCleartext = currentHost.allowCleartext
                            )
                        } else {
                            val refreshEx = refreshRes.exceptionOrNull()
                            val refreshUnauthorized = (refreshEx is HermesHttpException && refreshEx.statusCode == 401)
                            val refreshMsg = refreshEx?.message ?: ""
                            if (refreshUnauthorized || refreshMsg.contains("session_expired") || refreshMsg.contains("invalid_grant")) {
                                tokenVault.clearTokens(currentHost.id.value)
                                _status.value = HostStatus.AUTH_EXPIRED
                                gatewayClient.setAuthExpired("Session expired for ${currentHost.displayName}")
                                return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                            }
                        }
                    }

                    if (ticketResult.isFailure) {
                        val finalErr = ticketResult.exceptionOrNull()
                        val isFinalUnauthorized = (finalErr is HermesHttpException && finalErr.statusCode == 401)
                        if (isFinalUnauthorized) {
                            tokenVault.clearTokens(currentHost.id.value)
                            _status.value = HostStatus.AUTH_EXPIRED
                            gatewayClient.setAuthExpired("Session expired for ${currentHost.displayName}")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                        _status.value = HostStatus.ERROR
                        return Result.failure(finalErr ?: IOException("Failed to mint WebSocket ticket"))
                    }
                }
                ticket = ticketResult.getOrNull()
            }

            val wsUrl = convertHttpToWsUrl(currentHost.baseUrl)
            gatewayClient.connect(
                wsUrl = wsUrl,
                ticket = ticket,
                allowCleartext = currentHost.allowCleartext
            )
            gatewayClient.awaitGatewayReady(10_000)
            reconnectAttempt.set(0)
            _status.value = HostStatus.ONLINE
            updateLastSeen()
            Result.success(Unit)
        } catch (e: Exception) {
            _status.value = HostStatus.ERROR
            Result.failure(e)
        }
    }

    internal fun scheduleReconnect() {
        if (!autoReconnectEnabled.get() || !_host.value.enabled) return
        if (reconnectAttempt.get() >= MAX_RECONNECT_ATTEMPTS) {
            autoReconnectEnabled.set(false)
            _status.value = HostStatus.ERROR
            return
        }

        scope.launch {
            reconnectMutex.withLock {
                if (reconnectJob?.isActive == true) return@withLock
                if (!autoReconnectEnabled.get() || !_host.value.enabled) return@withLock
                val currentAttempt = reconnectAttempt.get()
                if (currentAttempt >= MAX_RECONNECT_ATTEMPTS) {
                    autoReconnectEnabled.set(false)
                    _status.value = HostStatus.ERROR
                    return@withLock
                }

                reconnectJob = scope.launch {
                    val attempt = reconnectAttempt.getAndIncrement()
                    val baseDelay = min(30_000L, 1000L * (1 shl min(attempt, 5)))
                    val jitter = Random.nextLong(0, 1000)
                    val totalDelay = baseDelay + jitter

                    delay(totalDelay)
                    if (!autoReconnectEnabled.get() || !_host.value.enabled) return@launch
                    val res = connectInternal()
                    if (res.isFailure && autoReconnectEnabled.get()) {
                        if (reconnectAttempt.get() >= MAX_RECONNECT_ATTEMPTS) {
                            autoReconnectEnabled.set(false)
                            _status.value = HostStatus.ERROR
                        } else {
                            scheduleReconnect()
                        }
                    }
                }
            }
        }
    }

    fun disconnect() {
        autoReconnectEnabled.set(false)
        reconnectAttempt.set(0)
        scope.launch {
            reconnectMutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
            }
        }
        gatewayClient.disconnect()
        try {
            restClient.client.dispatcher.cancelAll()
            restClient.client.connectionPool.evictAll()
        } catch (_: Exception) {}
        _status.value = HostStatus.OFFLINE
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun convertHttpToWsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) || trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "ws://$trimmed"
        }
        return when {
            wsBase.endsWith("/api/ws") -> wsBase
            wsBase.endsWith("/ws") -> wsBase.removeSuffix("/ws") + "/api/ws"
            else -> "$wsBase/api/ws"
        }
    }
}
