package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ManualEntryUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun add_manual_transaction_through_real_form() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val before = runBlocking { repository.snapshot().rows.map { it.id }.toSet() }
        try {
            rule.onNodeWithText("+ Add transaction").performClick()
            rule.onNodeWithText("Amount (INR)").performTextInput("512.34")
            rule.onNodeWithText("Save transaction").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { repository.snapshot().rows.any { it.id !in before && it.amountMinor == 51234L } } }
            rule.onNodeWithText("+ Add transaction").assertIsDisplayed()
            rule.onNodeWithText("₹512.34 · Debit").assertIsDisplayed()
        } finally {
            runBlocking { repository.snapshot().rows.filter { it.id !in before }.forEach { repository.delete(it.id) } }
        }
    }
}
