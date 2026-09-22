package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Rule
import org.junit.Test

class OptionalToolsNavigationUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @After fun finishActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries.flatMap { monitor.getActivitiesInStage(it).toList() }
                .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
    }

    @Test fun reminder_notification_opens_the_requested_optional_tool_tab() {
        launch(android.content.Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("open_optional_tools", "Payment reminders"))
        rule.onNodeWithText("Your recurring payment reminders").assertExists()
        rule.onNodeWithText("Nothing is paid or recorded automatically.").assertExists()
    }

    @Test fun widget_quick_add_opens_manual_transaction_entry() {
        launch(android.content.Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("open_add", true))
        rule.onNodeWithText("Add transaction").assertExists()
        rule.onNodeWithText("Amount (INR)").assertExists()
    }

    private fun launch(intent: android.content.Intent) {
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        rule.waitForIdle()
    }
}
