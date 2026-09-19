package `in`.financeministry.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AppLaunchSmokeTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchRendersPrivateAlphaShell() {
        activityRule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
        activityRule.onNodeWithText("+ Add transaction").assertIsDisplayed()
    }
}
