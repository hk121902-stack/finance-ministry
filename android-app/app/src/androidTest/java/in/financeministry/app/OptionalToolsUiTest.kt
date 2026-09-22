package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import `in`.financeministry.app.core.model.Direction
import `in`.financeministry.app.core.model.TransactionType
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OptionalToolsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun budgets_reminders_and_widget_consent_are_available_without_automatic_payments() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val marker = System.nanoTime().toString().takeLast(5)
        val food = runBlocking { repository.save(ManualInput("5200", Direction.Debit, System.currentTimeMillis(),
            TransactionType.Other, label = "Tools food $marker", category = "Food")) }
        val rent = runBlocking { repository.save(ManualInput("9000", Direction.Debit, System.currentTimeMillis(),
            TransactionType.Other, label = "Tools rent $marker", category = "Flat expenses")) }
        try {
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Optional tools").performScrollTo().performClick()
            rule.onNodeWithText("Budgets").assertExists()
            rule.onNodeWithText("+ Add budget").performClick()
            rule.onNodeWithTag("budget-category-Food").performClick()
            rule.onNodeWithTag("budget-amount").performTextInput("6000")
            rule.onNodeWithText("Alert me at 80%").performClick()
            rule.onNodeWithText("Save budget").performClick()
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("₹800.00 left", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("₹800.00 left", substring = true).assertExists()

            rule.onNodeWithText("Payment reminders").performClick()
            rule.onNodeWithText("+ Add reminder").performClick()
            rule.onNodeWithTag("reminder-title").performTextInput("Rent $marker")
            rule.onNodeWithTag("reminder-amount").performTextInput("9000")
            rule.onNodeWithTag("reminder-day").performTextReplacement("31")
            rule.onNodeWithTag("reminder-category-Flat expenses").performClick()
            rule.onNodeWithText("Save reminder").performClick()
            rule.onNodeWithText("Nothing is paid or recorded automatically.").assertExists()
            rule.waitUntil(10000) { rule.onAllNodesWithText("Mark paid").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Mark paid").performScrollTo().performClick()
            rule.onNodeWithText("Tools rent $marker", substring = true).performClick()
            rule.onNodeWithText("Link this payment").performClick()
            rule.waitUntil(10000) { runBlocking { repository.recurringReminders().single { it.title == "Rent $marker" }.lastLinkedTransactionId == rent } }
            assertNotNull(runBlocking { repository.get(rent) })

            rule.onNodeWithText("Home-screen widget").performClick()
            rule.onNodeWithText("Quick add shows no amounts by default.").assertExists()
            rule.onNodeWithTag("widget-summary-toggle").performClick()
            rule.onNodeWithText("Show financial amounts on your home screen?").assertExists()
            rule.onNodeWithText("Allow summary").performClick()
            assertTrue(repository.preferences.getBoolean("widget_summary", false))
        } finally {
            runBlocking {
                repository.recurringReminders().filter { it.title == "Rent $marker" }.forEach { repository.deleteRecurringReminder(it.id) }
                repository.budgets().filter { it.category == "Food" }.forEach { repository.deleteBudget(it.id) }
                repository.delete(food); repository.delete(rent)
            }
            repository.preferences.edit().remove("widget_summary").commit()
        }
    }

}
