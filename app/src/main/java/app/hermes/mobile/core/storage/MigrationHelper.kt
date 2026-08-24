package app.hermes.mobile.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.hermes.mobile.core.model.HermesConnection
import app.hermes.mobile.core.model.HostStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_connections")

object MigrationHelper {
    private val json = Json { ignoreUnknownKeys = true }
    private val connectionsKey = stringPreferencesKey("saved_connections")
    private val migrationCompletedKey = booleanPreferencesKey("migration_completed")

    suspend fun migrateLegacyConnections(context: Context, hostDao: HostDao): Boolean {
        return migrateLegacyConnections(context.dataStore, hostDao)
    }

    suspend fun migrateLegacyConnections(dataStore: DataStore<Preferences>, hostDao: HostDao): Boolean {
        try {
            val preferences = dataStore.data.firstOrNull() ?: return false
            val isCompleted = preferences[migrationCompletedKey] ?: false
            if (isCompleted) {
                return false
            }

            val raw = preferences[connectionsKey]
            if (!raw.isNullOrBlank()) {
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
            }

            dataStore.edit { prefs ->
                prefs[migrationCompletedKey] = true
                prefs.remove(connectionsKey)
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }
}
