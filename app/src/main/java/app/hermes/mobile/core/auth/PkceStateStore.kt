package app.hermes.mobile.core.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingAuthState(
    val hostId: String,
    val state: String,
    val codeVerifier: String,
    val baseUrl: String,
    val allowCleartext: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class PkceStateStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("hermes_pkce_auth_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun savePendingState(pending: PendingAuthState) {
        val serialized = json.encodeToString(pending)
        prefs.edit().putString("pending_${pending.state}", serialized).commit()
    }

    fun getPendingState(state: String): PendingAuthState? {
        val raw = prefs.getString("pending_$state", null) ?: return null
        return try {
            json.decodeFromString<PendingAuthState>(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun clearPendingState(state: String) {
        prefs.edit().remove("pending_$state").commit()
    }
}
