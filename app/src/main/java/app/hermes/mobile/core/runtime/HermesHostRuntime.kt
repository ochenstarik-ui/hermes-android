package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HermesServerStatus
import app.hermes.mobile.core.model.HostGatewayEvent
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.ConnectionState
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class HermesHostRuntime(
    initialHost: HermesHost,
    val restClient: HermesRestClient = HermesRestClient(),
    val tokenVault: TokenVault,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val gatewayClient: JsonRpcGatewayClient = JsonRpcGatewayClient(scope = scope)
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

    private var reconnectJob: Job? = null
    private var autoReconnectEnabled = false
    private var reconnectAttempt = 0

    init {
        scope.launch {
            gatewayClient.events.collect { event ->
                dispatchEvent(HostGatewayEvent(hostId, event))
            }
        }

        scope.launch {
            gatewayClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        _status.value = HostStatus.ONLINE
                        updateLastSeen()
                    }
                    is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                        _status.value = HostStatus.CONNECTING
                    }
                    is ConnectionState.AuthExpired -> {
                        autoReconnectEnabled = false
                        reconnectJob?.cancel()
                        _status.value = HostStatus.AUTH_EXPIRED
                    }
                    is ConnectionState.Failed -> {
                        _status.value = HostStatus.ERROR
                        if (autoReconnectEnabled) {
                            scheduleReconnect()
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (_status.value != HostStatus.AUTH_EXPIRED && _status.value != HostStatus.AUTH_REQUIRED) {
                            _status.value = HostStatus.OFFLINE
                        }
                        if (autoReconnectEnabled) {
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
        autoReconnectEnabled = true
        return connectInternal()
    }

    private suspend fun connectInternal(): Result<Unit> {
        val currentHost = _host.value
        _status.value = HostStatus.CONNECTING

        return try {
            val statusResult = restClient.getStatus(currentHost.baseUrl, currentHost.allowCleartext)
            val sStatus = statusResult.getOrNull() ?: HermesServerStatus()
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
                        val errMsg = refreshRes.exceptionOrNull()?.message ?: ""
                        if (errMsg.contains("401") || errMsg.contains("session_expired") || errMsg.contains("invalid_grant")) {
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
                    val errMsg = ticketResult.exceptionOrNull()?.message ?: ""
                    if (errMsg.contains("401") && tokens.refreshToken.isNotEmpty()) {
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
                            tokenVault.clearTokens(currentHost.id.value)
                            _status.value = HostStatus.AUTH_EXPIRED
                            gatewayClient.setAuthExpired("Session expired for ${currentHost.displayName}")
                            return Result.failure(IllegalStateException("Session expired. Please sign in again."))
                        }
                    }

                    if (ticketResult.isFailure) {
                        val finalErr = ticketResult.exceptionOrNull()
                        if (finalErr?.message?.contains("401") == true) {
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
            Result.success(Unit)
        } catch (e: Exception) {
            _status.value = HostStatus.ERROR
            Result.failure(e)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val baseDelay = min(30_000L, (1000L * (1 shl min(reconnectAttempt, 5))))
            val jitter = Random.nextLong(0, 1000)
            val totalDelay = baseDelay + jitter
            reconnectAttempt++

            delay(totalDelay)
            try {
                connectInternal()
            } catch (_: Exception) {
            }
        }
    }

    fun disconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        gatewayClient.disconnect()
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
