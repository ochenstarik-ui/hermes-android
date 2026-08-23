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
    fun saveTokens(connectionId: String, tokens: NativeAuthTokens)
    fun getTokens(connectionId: String): NativeAuthTokens?
    fun clearTokens(connectionId: String)
    fun getAllConnectionIds(): Set<String>
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

    override fun saveTokens(connectionId: String, tokens: NativeAuthTokens) {
        val serialized = json.encodeToString(tokens)
        prefs.edit().putString("conn_$connectionId", serialized).apply()
    }

    override fun getTokens(connectionId: String): NativeAuthTokens? {
        val raw = prefs.getString("conn_$connectionId", null) ?: return null
        return try {
            json.decodeFromString<NativeAuthTokens>(raw)
        } catch (e: Exception) {
            null
        }
    }

    override fun clearTokens(connectionId: String) {
        prefs.edit().remove("conn_$connectionId").apply()
    }

    override fun getAllConnectionIds(): Set<String> {
        return prefs.all.keys
            .filter { it.startsWith("conn_") }
            .map { it.removePrefix("conn_") }
            .toSet()
    }
}

class InMemoryTokenVault : TokenVault {
    private val storage = ConcurrentHashMap<String, NativeAuthTokens>()

    override fun saveTokens(connectionId: String, tokens: NativeAuthTokens) {
        storage[connectionId] = tokens
    }

    override fun getTokens(connectionId: String): NativeAuthTokens? {
        return storage[connectionId]
    }

    override fun clearTokens(connectionId: String) {
        storage.remove(connectionId)
    }

    override fun getAllConnectionIds(): Set<String> {
        return storage.keys.toSet()
    }
}
