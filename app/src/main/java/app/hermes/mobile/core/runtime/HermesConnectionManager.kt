package app.hermes.mobile.core.runtime

import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import app.hermes.mobile.core.model.HostGatewayEvent
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.network.JsonRpcGatewayClient
import app.hermes.mobile.core.security.TokenVault
import app.hermes.mobile.core.storage.HostDao
import app.hermes.mobile.core.storage.HostEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class HermesConnectionManager(
    val hostDao: HostDao,
    val tokenVault: TokenVault,
    val restClient: HermesRestClient = HermesRestClient(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val runtimeFactory: (CoroutineScope, HermesHost) -> HermesHostRuntime = { parentScope, host ->
        val childScope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        HermesHostRuntime(
            initialHost = host,
            restClient = restClient,
            gatewayClient = JsonRpcGatewayClient(scope = childScope),
            tokenVault = tokenVault,
            scope = childScope
        )
    }
) {
    private val runtimes = ConcurrentHashMap<HermesHostId, HermesHostRuntime>()

    private val _hosts = MutableStateFlow<List<HermesHost>>(emptyList())
    val hosts: StateFlow<List<HermesHost>> = _hosts.asStateFlow()

    private val _activeHostId = MutableStateFlow<HermesHostId?>(null)
    val activeHostId: StateFlow<HermesHostId?> = _activeHostId.asStateFlow()

    private val _allEvents = MutableSharedFlow<HostGatewayEvent>(extraBufferCapacity = 128)
    val allEvents: SharedFlow<HostGatewayEvent> = _allEvents.asSharedFlow()

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
                        _allEvents.emit(next)
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

    init {
        scope.launch {
            hostDao.getHostsFlow().collect { entities ->
                val list = entities.map { it.toDomain() }
                _hosts.value = list

                // Auto-sync runtimes with database hosts
                val validIds = list.map { it.id }.toSet()
                for ((id, rt) in runtimes) {
                    if (id !in validIds) {
                        rt.close()
                        runtimes.remove(id)
                    }
                }
                for (h in list) {
                    val existingRt = runtimes[h.id]
                    if (existingRt != null) {
                        existingRt.updateHost(h)
                    } else {
                        getOrCreateRuntime(h)
                    }
                }

                if (_activeHostId.value == null && list.isNotEmpty()) {
                    _activeHostId.value = list.first().id
                } else if (_activeHostId.value != null && list.none { it.id == _activeHostId.value }) {
                    _activeHostId.value = list.firstOrNull()?.id
                }
            }
        }
    }

    fun getRuntime(hostId: HermesHostId): HermesHostRuntime? {
        val existing = runtimes[hostId]
        if (existing != null) return existing

        val host = _hosts.value.find { it.id == hostId } ?: return null
        return getOrCreateRuntime(host)
    }

    fun getOrCreateRuntime(host: HermesHost): HermesHostRuntime {
        return runtimes.computeIfAbsent(host.id) {
            val rt = runtimeFactory(scope, host)
            // Forward events sequentially
            scope.launch {
                rt.events.collect { event ->
                    dispatchEvent(event)
                }
            }
            // Update host status in DB on change
            scope.launch {
                rt.status.collect { st ->
                    hostDao.updateHostStatus(host.id.value, st.name, System.currentTimeMillis())
                }
            }
            rt
        }
    }

    suspend fun addHost(host: HermesHost) {
        hostDao.insertOrUpdateHost(host.toEntity())
        getOrCreateRuntime(host)
    }

    suspend fun updateHost(host: HermesHost) {
        hostDao.insertOrUpdateHost(host.toEntity())
        val rt = runtimes[host.id]
        rt?.updateHost(host)
    }

    suspend fun removeHost(hostId: HermesHostId) {
        val rt = runtimes.remove(hostId)
        rt?.close()
        tokenVault.clearTokens(hostId.value)
        hostDao.deleteHost(hostId.value)
        if (_activeHostId.value == hostId) {
            _activeHostId.value = _hosts.value.firstOrNull { it.id != hostId }?.id
        }
    }

    suspend fun connectHost(hostId: HermesHostId): Result<Unit> {
        val host = _hosts.value.find { it.id == hostId }
            ?: return Result.failure(IllegalArgumentException("Host not found: ${hostId.value}"))
        val rt = getOrCreateRuntime(host)
        return rt.connect()
    }

    fun disconnectHost(hostId: HermesHostId) {
        runtimes[hostId]?.disconnect()
    }

    fun switchActiveHost(hostId: HermesHostId) {
        if (_hosts.value.any { it.id == hostId }) {
            _activeHostId.value = hostId
        }
    }

    suspend fun refreshAllHosts() {
        val currentHosts = hostDao.getHosts()
        _hosts.value = currentHosts.map { it.toDomain() }
    }

    private fun HostEntity.toDomain(): HermesHost {
        val status = HostStatus.fromStringOrOffline(lastKnownStatus)
        return HermesHost(
            id = HermesHostId(id),
            displayName = displayName,
            baseUrl = baseUrl,
            allowCleartext = allowCleartext,
            enabled = enabled,
            lastSeenAt = lastSeenAt,
            lastKnownStatus = status
        )
    }

    private fun HermesHost.toEntity(): HostEntity {
        return HostEntity(
            id = id.value,
            displayName = displayName,
            baseUrl = baseUrl,
            allowCleartext = allowCleartext,
            enabled = enabled,
            lastSeenAt = lastSeenAt,
            lastKnownStatus = lastKnownStatus.name
        )
    }
}
