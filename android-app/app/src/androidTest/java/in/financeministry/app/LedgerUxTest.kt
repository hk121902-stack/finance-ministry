package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

class LedgerUxTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun settings_are_separate_and_return_to_home() {
        rule.onNodeWithText("Erase all local data").assertDoesNotExist()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Recording notifications").assertExists()
        rule.onNodeWithText("Erase all local data").performScrollTo().assertExists()
        rule.onNodeWithText("Home").performScrollTo().performClick()
        rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
    }

    @Test fun repeating_active_filter_does_not_remove_saved_history() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val id = runBlocking { repository.save(`in`.financeministry.app.data.ManualInput("71.23", `in`.financeministry.app.core.model.Direction.Debit, System.currentTimeMillis(), `in`.financeministry.app.core.model.TransactionType.Other)) }
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Manual"))
            rule.onNodeWithText("Manual").performClick()
            rule.waitUntil(15000) {
                rule.onAllNodesWithText("Recorded this month").fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText("Loading transactions…").fetchSemanticsNodes().isEmpty()
            }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Manual"))
            rule.onNodeWithText("Manual").performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("₹71.23 · Money out"))
            rule.onNodeWithText("₹71.23 · Money out").assertIsDisplayed()
        } finally { runBlocking { repository.delete(id) } }
    }

    @Test fun cancel_changed_form_keeps_draft_until_discard_confirmed() {
        rule.onNodeWithText("+ Add transaction").performClick()
        rule.onNodeWithText("Amount (INR)").performTextInput("42.00")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithText("42.00").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Discard changes").performClick()
        rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
    }

    @Test fun money_in_choice_saves_credit_and_draft_survives_recreation() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val before = runBlocking { repository.snapshot().rows.map { it.id }.toSet() }
        try {
            rule.onNodeWithText("+ Add transaction").performClick()
            rule.onNodeWithText("Amount (INR)").performTextInput("62.19")
            rule.onNodeWithText("Money in").performClick()
            rule.activityRule.scenario.recreate()
            rule.onNodeWithText("62.19").assertExists()
            rule.onNodeWithText("Money in").assertIsSelected()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15000) { runBlocking { repository.snapshot().rows.any { it.id !in before && it.amountMinor == 6219L } } }
            val saved = runBlocking { repository.snapshot().rows.single { it.id !in before && it.amountMinor == 6219L } }
            assertEquals("Credit", saved.direction)
            assertEquals("Successful", saved.status)
        } finally { runBlocking { repository.snapshot().rows.filter { it.id !in before }.forEach { repository.delete(it.id) } } }
    }

    @Test fun incomplete_sms_can_be_reviewed_without_reentering_known_direction() {
        val app = rule.activity.application as FinanceMinistryApp
        val repository = app.container.repository
        val consent = repository.preferences.getBoolean("sms_disclosure", false)
        val before = runBlocking { repository.snapshot().rows.map { it.id }.toSet() }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.RECEIVE_SMS)
        try {
            repository.preferences.edit().putBoolean("sms_disclosure", true).commit()
            runBlocking { repository.ingest(`in`.financeministry.app.core.model.IncomingSms("SYNTHETIC", System.currentTimeMillis(), "Your account was debited via UPI.")) }
            rule.waitForIdle()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review transactions that need checking"))
            rule.onNodeWithText("Review transactions that need checking").performClick()
            rule.waitForIdle()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review transactions that need checking"))
            rule.onNodeWithText("Review transactions that need checking").performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Amount unknown · Money out"))
            rule.onNodeWithText("Amount unknown · Money out").performClick()
            rule.onNodeWithText("Check this transaction").assertExists()
            rule.onNodeWithText("Edit / confirm").performClick()
            rule.onNodeWithText("Money out").assertIsSelected()
            rule.onNodeWithText("Amount (INR)").performTextInput("81.25")
            rule.onNodeWithText("Transaction type — please choose: Not identified").performScrollTo().performClick()
            rule.onNodeWithText("Other").performClick()
            rule.onNodeWithText("Save transaction").performClick()
            try {
                rule.waitUntil(15000) { runBlocking { repository.snapshot().rows.any { it.id !in before && it.amountMinor == 8125L && it.reviewState == "Confirmed" } } }
            } catch (failure: Throwable) {
                throw AssertionError("Review save did not finish. UI state: ${rule.onRoot().printToString()}", failure)
            }
        } finally {
            repository.preferences.edit().putBoolean("sms_disclosure", consent).commit()
            runBlocking { repository.snapshot().rows.filter { it.id !in before }.forEach { repository.delete(it.id) } }
        }
    }
}
