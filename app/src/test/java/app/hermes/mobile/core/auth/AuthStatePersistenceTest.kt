package app.hermes.mobile.core.auth

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class AuthStatePersistenceTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val data = ConcurrentHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val storage: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else pending[key] = null
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) storage.clear()
                for ((k, v) in pending) {
                    if (v == null) storage.remove(k) else storage[k] = v
                }
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = mockk()
        every { mockContext.getSharedPreferences("hermes_pkce_auth_state", Context.MODE_PRIVATE) } returns fakePrefs
    }

    @Test
    fun testPendingAuthStateSurvivesProcessRecreation() {
        val stateStore1 = PkceStateStore(mockContext)
        val originalPending = PendingAuthState(
            hostId = "host-alpha",
            state = "state-uuid-9876",
            codeVerifier = "code-verifier-secure-random-12345",
            baseUrl = "https://hermes.example.com",
            allowCleartext = false,
            timestamp = 1700000000000L
        )

        // Save state before process death
        stateStore1.savePendingState(originalPending)

        // Simulate process death / new instance instantiation with same persistent storage
        val stateStore2 = PkceStateStore(mockContext)
        val restored = stateStore2.getPendingState("state-uuid-9876")

        assertNotNull("Restored auth state must not be null after process recreation", restored)
        assertEquals("host-alpha", restored?.hostId)
        assertEquals("state-uuid-9876", restored?.state)
        assertEquals("code-verifier-secure-random-12345", restored?.codeVerifier)
        assertEquals("https://hermes.example.com", restored?.baseUrl)
        assertEquals(false, restored?.allowCleartext)
        assertEquals(1700000000000L, restored?.timestamp)
    }

    @Test
    fun testClearPendingStateRemovesPersistedData() {
        val stateStore = PkceStateStore(mockContext)
        val pending = PendingAuthState(
            hostId = "host-beta",
            state = "state-to-clear",
            codeVerifier = "verifier-to-clear",
            baseUrl = "http://127.0.0.1:8080",
            allowCleartext = true
        )

        stateStore.savePendingState(pending)
        assertNotNull(stateStore.getPendingState("state-to-clear"))

        stateStore.clearPendingState("state-to-clear")
        assertNull("Cleared pending state must return null", stateStore.getPendingState("state-to-clear"))
    }

    @Test
    fun testNonExistentOrCorruptedStateReturnsNull() {
        val stateStore = PkceStateStore(mockContext)
        assertNull(stateStore.getPendingState("non-existent-state"))

        // Corrupted entry in storage
        fakePrefs.edit().putString("pending_corrupt", "{invalid json}").commit()
        assertNull(stateStore.getPendingState("corrupt"))
    }
}
