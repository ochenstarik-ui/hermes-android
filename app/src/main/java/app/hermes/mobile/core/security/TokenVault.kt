package app.hermes.mobile.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.hermes.mobile.core.model.NativeAuthTokens
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

interface TokenVault {
    fun saveTokens(hostId: String, tokens: NativeAuthTokens)
    fun getTokens(hostId: String): NativeAuthTokens?
    fun clearTokens(hostId: String)
    fun getAllHostIds(): Set<String>

    // Backwards-compatible aliases
    fun getAllConnectionIds(): Set<String> = getAllHostIds()
}

class EncryptedTokenVault(context: Context) : TokenVault {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "hermes_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        throw SecurityException("Keystore encryption required for token storage", e)
    }

    override fun saveTokens(hostId: String, tokens: NativeAuthTokens) {
        val serialized = json.encodeToString(tokens)
        prefs.edit().putString("conn_$hostId", serialized).apply()
    }

    override fun getTokens(hostId: String): NativeAuthTokens? {
        val raw = prefs.getString("conn_$hostId", null) ?: return null
        return try {
            json.decodeFromString<NativeAuthTokens>(raw)
        } catch (e: Exception) {
            null
        }
    }

    override fun clearTokens(hostId: String) {
        prefs.edit().remove("conn_$hostId").apply()
    }

    override fun getAllHostIds(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith("conn_") }
            .map { it.removePrefix("conn_") }
            .toSet()
    }
}

class InMemoryTokenVault : TokenVault {
    private val storage = ConcurrentHashMap<String, NativeAuthTokens>()

    override fun saveTokens(hostId: String, tokens: NativeAuthTokens) {
        storage[hostId] = tokens
    }

    override fun getTokens(hostId: String): NativeAuthTokens? {
        return storage[hostId]
    }

    override fun clearTokens(hostId: String) {
        storage.remove(hostId)
    }

    override fun getAllHostIds(): Set<String> {
        return storage.keys.toSet()
    }
}
