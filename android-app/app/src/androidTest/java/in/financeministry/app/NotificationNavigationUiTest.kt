package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NotificationNavigationUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun notification_actions_target_correct_records_and_do_not_replay_after_recreation() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<FinanceMinistryApp>()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().startActivitySync(
            android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val repository = app.container.repository
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val first = runBlocking { repository.save(`in`.financeministry.app.data.ManualInput("700.01", `in`.financeministry.app.core.model.Direction.Debit,
            System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other)) }
        val second = runBlocking { repository.save(`in`.financeministry.app.data.ManualInput("700.02", `in`.financeministry.app.core.model.Direction.Debit,
            System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other)) }
        try {
            for (id in listOf(first, second)) `in`.financeministry.app.sms.TransactionNotifications.post(app, runBlocking { repository.get(id)!! }, true)
            val edit = manager.activeNotifications.single { it.tag == first }.notification.actions.single { it.title.toString() == "Edit" }.actionIntent
            val view = manager.activeNotifications.single { it.tag == second }.notification.contentIntent
            edit.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Edit / confirm transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("700.01").assertExists()
            rule.onNodeWithText("Cancel").performScrollTo().performClick()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().recreate()
            }
            rule.waitForIdle()
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
            view.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("₹700.02").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Edit / confirm").assertIsDisplayed()
            runBlocking { repository.delete(second) }
            view.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("This transaction no longer exists.").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
        } finally {
            runBlocking { repository.delete(first); repository.delete(second) }
            // PendingIntent delivery can create another activity task. Finish test-owned
            // MainActivity instances before ActivityScenario performs its teardown.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.entries.flatMap { monitor.getActivitiesInStage(it).toList() }
                    .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            }
        }
    }

}
