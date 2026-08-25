package app.hermes.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermes.mobile.HermesApplication
import app.hermes.mobile.MainActivity
import app.hermes.mobile.core.model.HermesHost
import app.hermes.mobile.core.model.HermesHostId
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatViewModelScopeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<HermesApplication>()
        runBlocking {
            app.container.connectionManager.addHost(
                HermesHost(
                    id = HermesHostId("test-host-id"),
                    displayName = "Test Host",
                    baseUrl = "http://127.0.0.1:9119"
                )
            )
        }
    }

    @Test
    fun testChatStateSurvivesRecreation() {
        // Wait for New Session button and click
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("New Session", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("New Session", ignoreCase = true).performClick()

        // Wait for Create dialog button and click
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("Create", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Create", ignoreCase = true).performClick()
        
        // Wait for ChatScreen input placeholder
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("Message Hermes…", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        
        composeTestRule.onNodeWithText("Message Hermes…", ignoreCase = true)
            .performTextInput("Draft prompt before rotation")
            
        composeTestRule.waitForIdle()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        
        // Verify text state persisted across recreation
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithText("Draft prompt before rotation", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Draft prompt before rotation", substring = true).assertIsDisplayed()
    }
}
