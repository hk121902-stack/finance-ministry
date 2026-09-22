package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.ManualInput
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class SpendingInsightsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun overview_opens_personal_share_breakdown_and_category_expenses() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val id = runBlocking { repository.save(ManualInput("3000", Direction.Debit, System.currentTimeMillis(),
            TransactionType.Other, label = "Insight test dinner", category = "Food",
            ownership = SpendingOwnership.Group, groupLabel = "Dinner", personalShare = "1000")) }
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Spending breakdown"))
            rule.onNodeWithText("Spending breakdown").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithContentDescription("Open Food spending").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Based on recorded transactions; history may be incomplete.").assertExists()
            rule.onNodeWithContentDescription("Open Food spending").performClick()
            rule.onNodeWithText("Paid amount can differ from your share.").assertExists()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Insight test dinner"))
            rule.onNodeWithText("Your share ₹1,000.00 · Paid ₹3,000.00").assertExists()
            rule.onNodeWithText("Insight test dinner").performClick()
            rule.onNodeWithText("Edit / confirm").assertExists()
        } finally { runBlocking { repository.delete(id) } }
    }

    @Test fun breakdown_shows_refunds_separately_and_opens_category_budgets() {
        val repository = (rule.activity.application as FinanceMinistryApp).container.repository
        val id = runBlocking { repository.save(ManualInput("30", Direction.Credit, System.currentTimeMillis(),
            TransactionType.Refund, label = "Insight refund")) }
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Spending breakdown"))
            rule.onNodeWithText("Spending breakdown").performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Refunds received", substring = true))
            rule.onNodeWithText("Refunds received · ₹30.00").assertExists()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Set a category budget"))
            rule.onNodeWithText("Set a category budget").performClick()
            rule.onNodeWithText("Monthly category budgets").assertExists()
        } finally { runBlocking { repository.delete(id) } }
    }
}
