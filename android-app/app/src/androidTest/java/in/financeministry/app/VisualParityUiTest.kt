package `in`.financeministry.app

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class VisualParityUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun capture_approved_native_flows_for_proposal_comparison() {
        val app = ApplicationProvider.getApplicationContext<FinanceMinistryApp>()
        val repository = app.container.repository
        val variant = InstrumentationRegistry.getArguments().getString("visualVariant") ?: "light"
        val originalName = repository.preferences.getString("preferred_name", "").orEmpty()
        val originalOnboarding = repository.preferences.getBoolean("onboarding_complete", false)
        val ids = runBlocking {
            repository.preferences.edit().putString("preferred_name", "Himanshu").putBoolean("onboarding_complete", true).commit()
            listOf(
                repository.save(ManualInput("3000", Direction.Debit, System.currentTimeMillis() - 3000,
                    TransactionType.Other, label = "Group dinner", category = "Food", ownership = SpendingOwnership.Group,
                    groupLabel = "Friends", personalShare = "1000")),
                repository.save(ManualInput("2500", Direction.Debit, System.currentTimeMillis() - 2000,
                    TransactionType.Other, label = "Family groceries", category = "Food", ownership = SpendingOwnership.Family)),
                repository.save(ManualInput("500", Direction.Credit, System.currentTimeMillis() - 1000,
                    TransactionType.Other, label = "Repayment received")),
            ).also {
                repository.saveBudget("Food", "6000", 80)
                repository.saveRecurringReminder("Rent", "9000", 1, "Flat expenses")
            }
        }
        try {
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Money out · month").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Loading transactions…").fetchSemanticsNodes().isEmpty() }
            capture("$variant-overview.png")

            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Spending breakdown"))
            rule.onNodeWithText("Spending breakdown").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Open Food spending").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("Open Food spending").assertIsDisplayed()
            capture("$variant-spending-breakdown.png")

            rule.onNodeWithText("Back").performClick()
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Optional tools").performScrollTo().performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("₹2,500.00 left", substring = true).fetchSemanticsNodes().isNotEmpty() }
            capture("$variant-budget.png")
            rule.onNodeWithText("Payment reminders").performClick()
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Your recurring payment reminders").fetchSemanticsNodes().isNotEmpty() }
            capture("$variant-reminder.png")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.entries.flatMap { monitor.getActivitiesInStage(it).toList() }
                    .filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
            }
            runBlocking {
                repository.recurringReminders().filter { it.title == "Rent" }.forEach { repository.deleteRecurringReminder(it.id) }
                repository.budgets().filter { it.category == "Food" }.forEach { repository.deleteBudget(it.id) }
                ids.forEach { repository.delete(it) }
            }
            repository.preferences.edit().putString("preferred_name", originalName)
                .putBoolean("onboarding_complete", originalOnboarding).commit()
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(300)
        val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/finance-ministry-next-chapter")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
