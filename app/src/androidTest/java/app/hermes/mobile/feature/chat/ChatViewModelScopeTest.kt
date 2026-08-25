package app.hermes.mobile.feature.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermes.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatViewModelScopeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testChatStateSurvivesRecreation() {
        // Create session
        composeTestRule.onNodeWithText("New Session", ignoreCase = true).performClick()
        composeTestRule.onNodeWithText("Create", ignoreCase = true).performClick()
        
        composeTestRule.onNodeWithText("Message Hermes…", ignoreCase = true).assertIsDisplayed()
        
        composeTestRule.onNodeWithText("Message Hermes…", ignoreCase = true)
            .performTextInput("Draft prompt before rotation")
            
        composeTestRule.waitForIdle()
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        
        composeTestRule.onNodeWithText("Draft prompt before rotation", substring = true).assertExists()
    }
}
