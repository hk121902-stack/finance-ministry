package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
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
            rule.onNodeWithText("Enable SMS capture").assertExists()
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
        } finally { repository.preferences.edit().putBoolean("sms_disclosure", previous).commit() }
    }

    @Test fun add_manual_transaction_through_real_form() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val before = runBlocking { repository.snapshot().rows.map { it.id }.toSet() }
        try {
            rule.onNodeWithText("+ Add transaction").performClick()
            rule.onNodeWithText("Amount (INR)").performTextInput("512.34")
            rule.onNodeWithText("Save transaction").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { repository.snapshot().rows.any { it.id !in before && it.amountMinor == 51234L } } }
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
            rule.onNodeWithText("₹512.34 · Debit").performScrollTo().assertIsDisplayed()
        } finally {
            runBlocking { repository.snapshot().rows.filter { it.id !in before }.forEach { repository.delete(it.id) } }
        }
    }
}
