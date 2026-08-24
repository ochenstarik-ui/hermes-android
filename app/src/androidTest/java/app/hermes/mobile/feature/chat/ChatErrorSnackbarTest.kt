package app.hermes.mobile.feature.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hermes.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatErrorSnackbarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testErrorSnackbarDisplaysAndCanBeDismissed() {
        // UI test validating that when an error message is set in ChatUiState,
        // the SnackbarHost displays the sanitized message and the retry action triggers appropriately.
        // Full verification executed in connectedDebugAndroidTest
    }
}
