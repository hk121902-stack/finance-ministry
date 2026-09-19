package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NotificationNavigationUiTest {
    @get:Rule val rule = createEmptyComposeRule()
    @Test fun notification_actions_target_correct_records_and_do_not_replay_after_recreation() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<FinanceMinistryApp>()
        val repository = app.container.repository
        val onboardingComplete = repository.preferences.getBoolean("onboarding_complete", false)
        // This test exercises a returning user's notification path. Fresh-install onboarding
        // is verified separately by AppLaunchSmokeTest.
        repository.preferences.edit().putBoolean("onboarding_complete", true).commit()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().startActivitySync(
            android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val first = runBlocking { repository.save(`in`.financeministry.app.data.ManualInput("700.01", `in`.financeministry.app.core.model.Direction.Debit,
            System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other)) }
        val second = runBlocking { repository.save(`in`.financeministry.app.data.ManualInput("700.02", `in`.financeministry.app.core.model.Direction.Debit,
            System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other)) }
        try {
            for (id in listOf(first, second)) `in`.financeministry.app.sms.TransactionNotifications.post(app, runBlocking { repository.get(id)!! }, true)
            rule.waitUntil(15000) { manager.activeNotifications.map { it.tag }.containsAll(listOf(first, second)) }
            val edit = manager.activeNotifications.single { it.tag == first }.notification.actions.single { it.title.toString() == "Edit" }.actionIntent
            val view = manager.activeNotifications.single { it.tag == second }.notification.contentIntent
            val classify = manager.activeNotifications.single { it.tag == first }.notification.actions.single { it.title.toString() == "Categorize" }.actionIntent
            classify.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Categorize transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Amount (INR)").assertDoesNotExist()
            rule.onNodeWithText("Category: Other").performClick()
            rule.onNodeWithText("Food").performClick()
            rule.onNodeWithText("For someone else").performClick()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15000) { runBlocking { repository.get(first)?.category == "Food" } }
            org.junit.Assert.assertEquals("ForOther", runBlocking { repository.get(first)!!.ownership })
            org.junit.Assert.assertEquals(70001L, runBlocking { repository.get(first)!!.amountMinor })
            classify.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Categorize transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Family").performClick()
            rule.onNodeWithText("Category: Food").performClick()
            rule.onNodeWithText("Flat expenses").performClick()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15000) { runBlocking { repository.get(first)?.ownership == "Family" } }
            val family = runBlocking { repository.get(first)!! }
            org.junit.Assert.assertEquals("Flat expenses", family.category)
            org.junit.Assert.assertEquals(70001L, family.personalShareMinor)
            org.junit.Assert.assertNull(repaymentSummary(family))
            edit.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Edit / confirm transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("700.01").assertExists()
            rule.onNodeWithText("Category: Flat expenses").assertExists()
            rule.onNodeWithText("Amount (INR)").performTextReplacement("701.01")
            view.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Open another transaction?").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Keep editing").performClick()
            rule.onNodeWithText("701.01").assertExists()
            rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithText("Discard changes").performClick()
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single().recreate()
            }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
            view.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("₹700.02").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Edit / confirm").assertIsDisplayed()
            runBlocking { repository.delete(second) }
            view.send()
            rule.waitUntil(15000) { rule.onAllNodesWithText("This transaction no longer exists.").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("Open overview").assertIsDisplayed()
        } finally {
            runBlocking { repository.delete(first); repository.delete(second) }
            repository.preferences.edit().putBoolean("onboarding_complete", onboardingComplete).commit()
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
