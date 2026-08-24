package app.hermes.mobile.feature.chat

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import app.hermes.mobile.AppContainer
import app.hermes.mobile.AppViewModelFactory
import app.hermes.mobile.core.auth.PkceLoopbackAuthManager
import app.hermes.mobile.core.model.*
import app.hermes.mobile.core.network.HermesRestClient
import app.hermes.mobile.core.repository.UnifiedSessionRepository
import app.hermes.mobile.core.runtime.HermesConnectionManager
import app.hermes.mobile.core.security.EncryptedTokenVault
import app.hermes.mobile.core.storage.FakeHostDao
import app.hermes.mobile.core.storage.FakeUnifiedSessionDao
import app.hermes.mobile.core.storage.HermesDatabase
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatViewModelScopeTest {

    @Test
    fun testChatViewModelStorePreservesInstanceAcrossRecreation() = runTest {
        val sessionDao = FakeUnifiedSessionDao()
        val hostDao = FakeHostDao()
        val restClient = HermesRestClient()
        val tokenVault = mockk<EncryptedTokenVault>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connectionManager = HermesConnectionManager(hostDao, tokenVault, restClient, scope)
        val repository = UnifiedSessionRepository(connectionManager, sessionDao, scope)
        
        val container = object : AppContainer {
            override val db: HermesDatabase get() = throw NotImplementedError()
            override val tokenVault: EncryptedTokenVault get() = tokenVault
            override val restClient: HermesRestClient get() = restClient
            override val pkceAuthManager: PkceLoopbackAuthManager get() = throw NotImplementedError()
            override val connectionManager: HermesConnectionManager get() = connectionManager
            override val unifiedSessionRepo: UnifiedSessionRepository get() = repository
            override val applicationScope: CoroutineScope get() = scope
        }
        
        val sessionId = UnifiedSessionId("test-session-id")
        val factory = AppViewModelFactory(container, extraArg = sessionId)
        val viewModelStore = ViewModelStore()
        
        val provider1 = ViewModelProvider(viewModelStore, factory)
        val vm1 = provider1[ChatViewModel::class.java]
        
        vm1.updateInputText("Draft text before config change")
        assertEquals("Draft text before config change", vm1.uiState.value.inputText)
        
        // Simulate activity recreation with the same ViewModelStore
        val provider2 = ViewModelProvider(viewModelStore, factory)
        val vm2 = provider2[ChatViewModel::class.java]
        
        assertSame("ViewModel instance must be preserved across recreation via ViewModelStore", vm1, vm2)
        assertEquals("Draft text before config change", vm2.uiState.value.inputText)
        
        // Clear ViewModelStore (simulate navigating away / onDestroy)
        viewModelStore.clear()
        
        // Verify that after clear, a new VM is instantiated
        val provider3 = ViewModelProvider(viewModelStore, factory)
        val vm3 = provider3[ChatViewModel::class.java]
        assertNotSame("New ViewModel instance should be created after ViewModelStore is cleared", vm1, vm3)
    }
}