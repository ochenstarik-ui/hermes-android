package app.hermes.mobile.core.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import app.hermes.mobile.core.model.HermesConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationOnceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }
    private val connectionsKey = stringPreferencesKey("saved_connections")
    private val migrationCompletedKey = booleanPreferencesKey("migration_completed")

    @Test
    fun testMigrationRunsOnceAndSetsCompletionFlagAndClearsLegacy() = runBlocking {
        val testFile = tempFolder.newFile("datastore_test_1.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { testFile })
        val hostDao = FakeHostDao()

        // 1. Prepopulate legacy DataStore with connections
        val legacyConnections = listOf(
            HermesConnection(
                id = "c1",
                name = "Legacy Host 1",
                baseUrl = "https://legacy1.example.com",
                allowCleartext = false,
                createdAt = 1000L
            ),
            HermesConnection(
                id = "c2",
                name = "Legacy Host 2",
                baseUrl = "http://legacy2.example.com",
                allowCleartext = true,
                createdAt = 2000L
            )
        )
        dataStore.edit { preferences ->
            preferences[connectionsKey] = json.encodeToString(legacyConnections)
        }

        // 2. Run migration first time
        val migratedFirst = MigrationHelper.migrateLegacyConnections(dataStore, hostDao)
        assertTrue("First migration run must return true", migratedFirst)

        // 3. Verify hosts are in DAO
        val hosts = hostDao.getHosts()
        assertEquals(2, hosts.size)
        assertEquals("Legacy Host 1", hosts.find { it.id == "c1" }?.displayName)
        assertEquals("Legacy Host 2", hosts.find { it.id == "c2" }?.displayName)

        // 4. Verify completion flag is set and legacy key is removed
        val prefsAfter = dataStore.data.first()
        assertEquals(true, prefsAfter[migrationCompletedKey])
        assertNull("Legacy connections key must be cleared after successful migration", prefsAfter[connectionsKey])

        // 5. Subsequent run must be a no-op (return false) and not re-process
        val migratedSecond = MigrationHelper.migrateLegacyConnections(dataStore, hostDao)
        assertFalse("Second migration run must return false (skipped)", migratedSecond)
    }

    @Test
    fun testMigrationRetriesIfFlagNotSet() = runBlocking {
        val testFile = tempFolder.newFile("datastore_test_2.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { testFile })
        val hostDao = FakeHostDao()

        val legacyConnections = listOf(
            HermesConnection(
                id = "c3",
                name = "Legacy Host 3",
                baseUrl = "https://legacy3.example.com",
                createdAt = 3000L
            )
        )
        dataStore.edit { preferences ->
            preferences[connectionsKey] = json.encodeToString(legacyConnections)
        }

        // Run migration
        MigrationHelper.migrateLegacyConnections(dataStore, hostDao)

        val hosts = hostDao.getHosts()
        assertEquals(1, hosts.size)
        assertEquals("c3", hosts[0].id)
    }
}
