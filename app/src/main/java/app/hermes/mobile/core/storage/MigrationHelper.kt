package app.hermes.mobile.core.storage

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import app.hermes.mobile.core.model.HermesConnection
import app.hermes.mobile.core.model.HostStatus
import app.hermes.mobile.core.repository.dataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json

object MigrationHelper {
    private val json = Json { ignoreUnknownKeys = true }
    private val connectionsKey = stringPreferencesKey("saved_connections")

    suspend fun migrateLegacyConnections(context: Context, hostDao: HostDao) {
        try {
            val preferences = context.dataStore.data.firstOrNull() ?: return
            val raw = preferences[connectionsKey] ?: return
            if (raw.isBlank()) return

            val legacyList = json.decodeFromString<List<HermesConnection>>(raw)
            for (legacy in legacyList) {
                val existing = hostDao.getHost(legacy.id)
                if (existing == null) {
                    hostDao.insertOrUpdateHost(
                        HostEntity(
                            id = legacy.id,
                            displayName = legacy.name,
                            baseUrl = legacy.baseUrl,
                            allowCleartext = legacy.allowCleartext,
                            enabled = true,
                            lastSeenAt = legacy.createdAt,
                            lastKnownStatus = HostStatus.OFFLINE.name,
                            certificateFingerprint = null
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore migration failure gracefully
        }
    }
}
