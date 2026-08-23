package app.hermes.mobile.core.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.hermes.mobile.core.model.HermesConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_connections")

class ConnectionRepository(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val connectionsKey = stringPreferencesKey("saved_connections")

    val connections: Flow<List<HermesConnection>> = context.dataStore.data.map { preferences ->
        val raw = preferences[connectionsKey] ?: return@map emptyList()
        try {
            json.decodeFromString<List<HermesConnection>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveConnection(connection: HermesConnection) {
        context.dataStore.edit { preferences ->
            val raw = preferences[connectionsKey]
            val currentList = if (!raw.isNullOrEmpty()) {
                try {
                    json.decodeFromString<List<HermesConnection>>(raw).toMutableList()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            val index = currentList.indexOfFirst { it.id == connection.id }
            if (index >= 0) {
                currentList[index] = connection
            } else {
                currentList.add(connection)
            }

            preferences[connectionsKey] = json.encodeToString(currentList)
        }
    }

    suspend fun removeConnection(connectionId: String) {
        context.dataStore.edit { preferences ->
            val raw = preferences[connectionsKey] ?: return@edit
            try {
                val currentList = json.decodeFromString<List<HermesConnection>>(raw).toMutableList()
                currentList.removeAll { it.id == connectionId }
                preferences[connectionsKey] = json.encodeToString(currentList)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun getConnection(connectionId: String): HermesConnection? {
        return connections.firstOrNull()?.find { it.id == connectionId }
    }
}
