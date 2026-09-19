package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManualEntryUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun resume_refreshes_capture_status_without_disabling_manual_entry() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val previous = repository.preferences.getBoolean("sms_disclosure", false)
        try {
            repository.preferences.edit().putBoolean("sms_disclosure", false).commit()
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("SMS and past messages").performClick()
            rule.onNodeWithText("Enable SMS capture").assertExists()
            rule.onNodeWithText("Back").performClick()
            rule.onNodeWithText("Home").performClick()
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
        } finally { repository.preferences.edit().putBoolean("sms_disclosure", previous).commit() }
    }

    @Test fun add_manual_transaction_through_real_form() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val before = runBlocking { repository.snapshot().rows.map { it.id }.toSet() }
        try {
            rule.onNodeWithText("+ Add transaction").performClick()
            rule.onNodeWithText("Amount (INR)").performTextInput("512.34")
            rule.onNodeWithText("Save transaction").performSemanticsAction(SemanticsActions.OnClick)
            try {
                rule.waitUntil(15000) { runBlocking { repository.snapshot().rows.any { it.id !in before && it.amountMinor == 51234L } } }
            } catch (failure: Throwable) {
                throw AssertionError("Save did not finish. UI state: ${rule.onRoot().printToString()}", failure)
            }
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
            val savedRow = hasText("₹512.34") and hasClickAction()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(savedRow)
            rule.onNode(savedRow).assertIsDisplayed()
            val saved = runBlocking { repository.snapshot().rows.single { it.id !in before && it.amountMinor == 51234L } }
            assertEquals("Debit", saved.direction)
        } finally {
            runBlocking { repository.snapshot().rows.filter { it.id !in before }.forEach { repository.delete(it.id) } }
        }
    }

    @Test fun manual_form_keeps_date_source_and_notes_in_more_details() {
        rule.onNodeWithText("+ Add transaction").performClick()
        rule.onNodeWithText("More details · date, source and notes").assertIsDisplayed()
        rule.onAllNodes(hasText("Date:", substring = true)).assertCountEquals(0)
    }
}
