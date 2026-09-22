package `in`.financeministry.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import `in`.financeministry.app.core.model.*
import `in`.financeministry.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RepaymentUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun gift_toggle_counts_full_amount_without_creating_debt() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val before = runBlocking { r.snapshot().rows.map { it.id }.toSet() }
        try {
            rule.onNodeWithText("+ Add transaction").performClick()
            rule.onNodeWithText("Amount (INR)").performTextInput("123.45")
            rule.onNodeWithText("For someone else").performScrollTo().performClick()
            rule.onNodeWithText("No · gift or treat").performScrollTo().performClick()
            rule.onNodeWithText("Save transaction").performClick()
            rule.waitUntil(15000) { runBlocking { r.snapshot().rows.any { it.id !in before } } }
            val saved = runBlocking { r.snapshot().rows.single { it.id !in before } }
            assertFalse(saved.repaymentExpected)
            assertEquals(12345L, RepaymentAccounting.personal(saved))
            assertEquals(0L, RepaymentAccounting.owed(saved))
        } finally { runBlocking { r.snapshot().rows.filter { it.id !in before }.forEach { r.delete(it.id) } } }
    }

    @Test fun cash_partial_repayment_is_recorded_from_monthly_owed_drilldown() {
        val r = (rule.activity.application as FinanceMinistryApp).container.repository
        val id = runBlocking { r.save(ManualInput("3000", Direction.Debit, System.currentTimeMillis(),
            TransactionType.Other, label = "Repayment test dinner", ownership = SpendingOwnership.Group,
            groupLabel = "Test flatmates", personalShare = "1000")) }
        var receiptId: String? = null
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Open repayments"))
            rule.onNodeWithContentDescription("Open repayments").performClick()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Test flatmates").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Test flatmates").performClick()
            rule.onNodeWithText("Repayment test dinner").performClick()
            rule.onNodeWithText("Record repayment").performScrollTo().performClick()
            rule.onNodeWithText("Repayment amount (INR)").performTextInput("500")
            rule.onNodeWithText("Record cash repayment").performClick()
            rule.onNodeWithText("Save repayment").performScrollTo().performClick()
            rule.waitUntil(15000) { runBlocking { r.repaymentsFor(id).size == 1 } }
            val repayment = runBlocking { r.repaymentsFor(id).single() }
            receiptId = repayment.incomingId
            assertEquals(50000L, repayment.amountMinor)
            assertEquals(150000L, RepaymentAccounting.owed(runBlocking { r.get(id)!! }))
            rule.onNodeWithText("Edit / confirm").performScrollTo().performClick()
            rule.onNodeWithText("Amount (INR)").performTextReplacement("2999.00")
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15000) { rule.onAllNodesWithText("Edit / confirm transaction").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("2999.00").assertExists()
        } finally {
            runBlocking {
                r.repaymentsFor(id).forEach { r.removeRepayment(it.id) }
                r.delete(id)
                receiptId?.let { r.delete(it) }
            }
        }
    }
}
