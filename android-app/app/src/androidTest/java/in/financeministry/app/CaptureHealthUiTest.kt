package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class CaptureHealthUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun health_distinguishes_capture_notifications_and_observed_errors_with_manual_recovery() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val prefs = repository.preferences
        val oldNotifications = prefs.getBoolean("notifications", true)
        val oldError = prefs.getBoolean("capture_error", false)
        val oldLast = prefs.getLong("last_capture_at", 0)
        try {
            prefs.edit().putBoolean("notifications", false).putBoolean("capture_error", true)
                .putLong("last_capture_at", 1_790_070_000_000L).commit()
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("SMS and past messages").performScrollTo().performClick()
            rule.onNodeWithText("Capture health").performScrollTo().performClick()
            rule.onNodeWithText("Off in app").assertExists()
            rule.onNodeWithText("Last recorded SMS").assertExists()
            rule.onNodeWithText(transactionTime(1_790_070_000_000L)).assertExists()
            rule.onNodeWithText("A recording error was detected").assertExists()
            rule.onNodeWithText("Notifications do not control SMS capture.").assertExists()
            rule.onNodeWithText("Add missing transaction").performScrollTo().performClick()
            rule.onNodeWithText("Amount (INR)").assertExists()
        } finally {
            prefs.edit().putBoolean("notifications", oldNotifications).putBoolean("capture_error", oldError)
                .putLong("last_capture_at", oldLast).commit()
        }
    }
}
