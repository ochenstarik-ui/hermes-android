package app.hermes.mobile.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.hermes.mobile.core.model.NativeAuthTokens
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

interface TokenVault {
    fun saveTokens(hostId: String, tokens: NativeAuthTokens)
    fun getTokens(hostId: String): NativeAuthTokens?
    fun clearTokens(hostId: String)
    fun getAllHostIds(): Set<String>
}

class EncryptedTokenVault(private val context: Context) : TokenVault {
    private val logger = Logger.getLogger(EncryptedTokenVault::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = initPrefs()

    private fun initPrefs(): SharedPreferences? {
        return try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            logger.warning("EncryptedSharedPreferences failed to initialize: ${e.message}. Attempting recovery by wiping corrupted preferences.")
            try {
                context.deleteSharedPreferences("hermes_secure_tokens")
                createEncryptedPrefs()
            } catch (e2: Exception) {
                logger.severe("EncryptedSharedPreferences recovery failed: ${e2.message}")
                null
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "hermes_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun saveTokens(hostId: String, tokens: NativeAuthTokens) {
        val p = prefs ?: initPrefs() ?: return
        val normalizedTokens = if (tokens.expiresAt == 0L && tokens.expiresIn != null && tokens.expiresIn > 0) {
            tokens.copy(expiresAt = System.currentTimeMillis() / 1000 + tokens.expiresIn)
        } else {
            tokens
        }
        val serialized = json.encodeToString(normalizedTokens)
        p.edit().putString("conn_$hostId", serialized).commit()
    }

    override fun getTokens(hostId: String): NativeAuthTokens? {
        val p = prefs ?: initPrefs() ?: return null
        val raw = p.getString("conn_$hostId", null) ?: return null
        return try {
            val tokens = json.decodeFromString<NativeAuthTokens>(raw)
            if (tokens.expiresAt == 0L && tokens.expiresIn != null && tokens.expiresIn > 0) {
                tokens.copy(expiresAt = System.currentTimeMillis() / 1000 + tokens.expiresIn)
            } else {
                tokens
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun clearTokens(hostId: String) {
        val p = prefs ?: initPrefs() ?: return
        p.edit().remove("conn_$hostId").commit()
    }

    override fun getAllHostIds(): Set<String> {
        val p = prefs ?: initPrefs() ?: return emptySet()
        return p.all.keys
            .filter { it.startsWith("conn_") }
            .map { it.removePrefix("conn_") }
            .toSet()
    }
}

class InMemoryTokenVault : TokenVault {
    private val storage = ConcurrentHashMap<String, NativeAuthTokens>()

    override fun saveTokens(hostId: String, tokens: NativeAuthTokens) {
        val normalized = if (tokens.expiresAt == 0L && tokens.expiresIn != null && tokens.expiresIn > 0) {
            tokens.copy(expiresAt = System.currentTimeMillis() / 1000 + tokens.expiresIn)
        } else {
            tokens
        }
        storage[hostId] = normalized
    }

    override fun getTokens(hostId: String): NativeAuthTokens? {
        val tokens = storage[hostId] ?: return null
        return if (tokens.expiresAt == 0L && tokens.expiresIn != null && tokens.expiresIn > 0) {
            tokens.copy(expiresAt = System.currentTimeMillis() / 1000 + tokens.expiresIn)
        } else {
            tokens
        }
    }

    override fun clearTokens(hostId: String) {
        storage.remove(hostId)
    }

    override fun getAllHostIds(): Set<String> {
        return storage.keys.toSet()
    }
}
